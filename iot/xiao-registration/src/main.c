#include "board.h"
#include <errno.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/settings/settings.h>
#include <zephyr/drivers/uart.h>

#define NUS_UUID(value) BT_UUID_128_ENCODE(value, 0xb5a3, 0xf393, 0xe0a9, 0xe50e24dcca9e)
static struct bt_uuid_128 service_uuid = BT_UUID_INIT_128(NUS_UUID(0x6e400001));
static struct bt_uuid_128 rx_uuid = BT_UUID_INIT_128(NUS_UUID(0x6e400002));
static struct bt_uuid_128 tx_uuid = BT_UUID_INIT_128(NUS_UUID(0x6e400003));
static struct kr_core core;
static struct bt_conn *peer;
static uint32_t generation;
static bool ready, busy;
static struct kr_frame incoming;
static char frame[KR_FRAME_MAX + 1], response[KR_RESPONSE_MAX];
K_MUTEX_DEFINE(transport_lock);
K_SEM_DEFINE(request_ready, 0, 1);

static ssize_t receive(struct bt_conn *conn, const struct bt_gatt_attr *attr,
	const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	ARG_UNUSED(attr);
	if (offset || (flags & (BT_GATT_WRITE_FLAG_PREPARE | BT_GATT_WRITE_FLAG_CMD)) || !len || len > 20) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	k_mutex_lock(&transport_lock, K_FOREVER);
	if (conn != peer || busy) {
		k_mutex_unlock(&transport_lock);
		return BT_GATT_ERR(BT_ATT_ERR_INSUFFICIENT_RESOURCES);
	}
	int framed = kr_frame_push(&incoming, buf, len);
	if (framed < 0) {
		kr_frame_reset(&incoming);
		k_mutex_unlock(&transport_lock);
		bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	if (framed == 1) {
		ready = busy = true;
		k_sem_give(&request_ready);
	}
	k_mutex_unlock(&transport_lock);
	return len;
}

static void subscribed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	printk("NUS notify=%u\n", value == BT_GATT_CCC_NOTIFY);
}

BT_GATT_SERVICE_DEFINE(nus,
	BT_GATT_PRIMARY_SERVICE(&service_uuid),
	BT_GATT_CHARACTERISTIC(&rx_uuid.uuid, BT_GATT_CHRC_WRITE, BT_GATT_PERM_WRITE, NULL, receive, NULL),
	BT_GATT_CHARACTERISTIC(&tx_uuid.uuid, BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(subscribed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE));

static void connected(struct bt_conn *conn, uint8_t error)
{
	if (error) { return; }
	k_mutex_lock(&transport_lock, K_FOREVER);
	peer = bt_conn_ref(conn);
	++generation;
	kr_frame_reset(&incoming); ready = busy = false;
	k_mutex_unlock(&transport_lock);
	printk("BLE connected\n");
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	k_mutex_lock(&transport_lock, K_FOREVER);
	if (peer == conn) {
		bt_conn_unref(peer); peer = NULL;
		++generation;
		kr_frame_reset(&incoming); ready = busy = false;
	}
	k_mutex_unlock(&transport_lock);
	printk("BLE disconnected reason=%u; incomplete frame discarded\n", reason);
}

BT_CONN_CB_DEFINE(callbacks) = {.connected = connected, .disconnected = disconnected};

static int respond(struct bt_conn *conn, const char *text)
{
	size_t n = strlen(text);
	int64_t deadline = k_uptime_get() + 10000;
	for (size_t offset = 0; offset < n;) {
		size_t count = MIN((size_t)20, n - offset);
		int result = bt_gatt_notify(conn, &nus.attrs[4], text + offset, count);
		if (!result) { offset += count; }
		else if ((result == -ENOMEM || result == -EAGAIN) && k_uptime_get() < deadline) {
			k_msleep(5); /* Transport backpressure only, not a matching queue delay. */
		} else { return result; }
	}
	return 0;
}

static void boot_fault(const char *stage, int error)
{
	/* Keep USB diagnostics visible even when the monitor opens after boot. */
	for (;;) {
		printk("BOOT failed stage=%s error=%d; no erase or key replacement\n", stage, error);
		k_sleep(K_SECONDS(5));
	}
}

int main(void)
{
	printk("BOOT %s stage=storage\n", KR_FIRMWARE_VERSION);
	int result = kr_board_open(&core);
	if (result) {
		core.storage_error = true;
		boot_fault("storage/identity", result);
	}
	printk("BOOT stage=bluetooth storedVersion=%lld timeReady=0\n", (long long)core.current.version);
	result = bt_enable(NULL);
	if (!result) { result = settings_load(); }
	if (result) { boot_fault("bluetooth", result); }
	const struct bt_data ad[] = {BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
		BT_DATA_BYTES(BT_DATA_UUID128_ALL, NUS_UUID(0x6e400001))};
	const struct bt_data scan[] = {BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME,
		sizeof(CONFIG_BT_DEVICE_NAME) - 1)};
	result = bt_le_adv_start(BT_LE_ADV_CONN_FAST_1, ad, ARRAY_SIZE(ad), scan, ARRAY_SIZE(scan));
	if (result) { boot_fault("advertising", result); }
	printk("BOOT NUS ready; registration window=60s; settingsPolicy=UNRESOLVED\n");
	int64_t start = k_uptime_get(), heartbeat = start;
	bool window_closed = false, light = false, console_open = false;
	const struct device *console = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
	for (;;) {
		uint32_t dtr = 0;
		if (!uart_line_ctrl_get(console, UART_LINE_CTRL_DTR, &dtr)) {
			if (dtr && !console_open) {
				printk("DIAG %s NUS ready; settingsPolicy=UNRESOLVED; storedVersion=%lld\n",
					KR_FIRMWARE_VERSION, (long long)core.current.version);
				kr_board_print_identity(&core.identity);
			}
			console_open = dtr != 0;
		}
		if (!k_sem_take(&request_ready, K_MSEC(250))) {
			struct bt_conn *conn = NULL;
			uint32_t request_generation;
			k_mutex_lock(&transport_lock, K_FOREVER);
			request_generation = generation;
			if (ready && peer) {
				memcpy(frame, incoming.bytes, incoming.size);
				conn = bt_conn_ref(peer); ready = false;
			}
			k_mutex_unlock(&transport_lock);
			if (conn) {
				kr_request(&core, frame, kr_board_status(), !core.current.magic &&
					k_uptime_get() - start < 60000, response);
				result = respond(conn, response);
				printk("NUS response bytes=%u result=%d storedVersion=%lld\n",
					(unsigned)strlen(response), result, (long long)core.current.version);
				/* A partial JSON response cannot be followed by a new one safely. */
				if (result) { bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN); }
				bt_conn_unref(conn);
				k_mutex_lock(&transport_lock, K_FOREVER);
				if (generation == request_generation) { busy = false; kr_frame_reset(&incoming); }
				k_mutex_unlock(&transport_lock);
			}
		}
		int64_t now = k_uptime_get();
		if (!window_closed && !core.current.magic && now - start >= 60000) {
			bt_le_adv_stop(); window_closed = true;
			printk("Registration window closed; reboot to reopen\n");
		}
		if (now - heartbeat >= 5000) {
			heartbeat = now; light = !light; kr_board_led(light);
			struct kr_status status = kr_board_status();
			printk("HEALTH uptime=%lld batteryMv=%d charging=%d storageError=%u\n",
				(long long)(now / 1000), status.battery_mv, status.charging, core.storage_error);
		}
	}
}

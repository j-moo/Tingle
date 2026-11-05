#include "board.h"
#include "nus.h"
#include <errno.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/sys/atomic.h>

static struct bt_uuid_128 service_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400001));
static struct bt_uuid_128 rx_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400002));
static struct bt_uuid_128 tx_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400003));
static struct kr_core core;
static struct k_sem *peer_wake;
static struct kr_peer_plan peer_plan;
static bool peer_pending;
static atomic_t has_settings;
static atomic_t reset_pending, reset_history, reset_recovery;
static int64_t window_deadline;
static struct bt_conn *peer;
static uint32_t generation;
static bool ready, busy;
static struct kr_frame incoming;
static char frame[KR_FRAME_MAX + 1], response[KR_RESPONSE_MAX];
K_MUTEX_DEFINE(transport_lock);
K_MUTEX_DEFINE(core_lock);
K_MUTEX_DEFINE(peer_plan_lock);
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

int kr_nus_init(struct k_sem *wake)
{
	peer_wake = wake;
	printk("BOOT %s stage=storage\n", KR_FIRMWARE_VERSION);
	int result = kr_board_open(&core);
	atomic_set(&has_settings, core.current.magic != 0);
	atomic_set(&reset_pending, core.reset.phase == KR_RESETTING);
	atomic_set(&reset_history, core.reset.ownership_epoch != 0);
	atomic_set(&reset_recovery, core.reset.ownership_epoch && !core.current.magic);
	return result;
}

bool kr_nus_reset_pending(void) { return atomic_get(&reset_pending) != 0; }
bool kr_nus_lab_allowed(void) { return !atomic_get(&reset_history) && !atomic_get(&reset_pending); }

/* Called by main only after stopping peer radio and disabling the synthetic engine. */
const char *kr_nus_finish_reset(void)
{
	k_mutex_lock(&core_lock, K_FOREVER);
	const char *error = kr_reset_resume(&core);
	atomic_set(&reset_pending, core.reset.phase == KR_RESETTING);
	atomic_set(&has_settings, core.current.magic != 0);
	atomic_set(&reset_recovery, core.reset.ownership_epoch && !core.current.magic);
	if (!error) { kr_nus_start_window(); }
	k_mutex_unlock(&core_lock);
	return error;
}

static void publish_peer_plan(const struct kr_peer_plan *plan)
{
	if (!peer_wake) {
		return;
	}
	k_mutex_lock(&peer_plan_lock, K_FOREVER);
	peer_plan = *plan;
	peer_pending = true;
	k_mutex_unlock(&peer_plan_lock);
	k_sem_give(peer_wake);
}

bool kr_nus_take_peer_plan(struct kr_peer_plan *out)
{
	k_mutex_lock(&peer_plan_lock, K_FOREVER);
	bool pending = peer_pending;
	if (pending) {
		*out = peer_plan;
		peer_pending = false;
	}
	k_mutex_unlock(&peer_plan_lock);
	return pending;
}

void kr_nus_start_window(void)
{
	window_deadline = k_uptime_get() + 60000;
}

bool kr_nus_can_advertise(void)
{
	return atomic_get(&has_settings) || atomic_get(&reset_pending) ||
		atomic_get(&reset_recovery) || k_uptime_get() < window_deadline;
}

void kr_nus_poll_console(bool blink)
{
	static int64_t heartbeat;
	static bool light, console_open;
	const struct device *console = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
	uint32_t dtr;
	if (!uart_line_ctrl_get(console, UART_LINE_CTRL_DTR, &dtr)) {
		if (dtr && !console_open) {
			k_mutex_lock(&core_lock, K_FOREVER);
			printk("DIAG %s NUS ready; settingsPolicy=%s; storedVersion=%lld\n",
				KR_FIRMWARE_VERSION, core.platform.issue_settings_nonce ? "DEV_SIGNED_V1" : "UNRESOLVED",
				(long long)core.current.version);
			kr_board_print_identity(&core.identity);
			k_mutex_unlock(&core_lock);
		}
		console_open = dtr != 0;
	}
	int64_t now = k_uptime_get();
	if (now - heartbeat >= 5000) {
		heartbeat = now;
		if (blink) {
			light = !light;
			kr_board_led(light);
		}
		k_mutex_lock(&core_lock, K_FOREVER);
		struct kr_status status = kr_board_status();
		printk("HEALTH uptime=%lld batteryMv=%d charging=%d storageError=%u\n",
			(long long)(now / 1000), status.battery_mv, status.charging, core.storage_error);
		k_mutex_unlock(&core_lock);
	}
}

static void process_requests(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);
	for (;;) {
		k_sem_take(&request_ready, K_FOREVER);
		struct bt_conn *conn = NULL;
		k_mutex_lock(&transport_lock, K_FOREVER);
		uint32_t request_generation = generation;
		if (ready && peer) {
			memcpy(frame, incoming.bytes, incoming.size);
			conn = bt_conn_ref(peer);
			ready = false;
		}
		k_mutex_unlock(&transport_lock);
		if (!conn) {
			continue;
		}

		k_mutex_lock(&core_lock, K_FOREVER);
		kr_request(&core, frame, kr_board_status(), !core.current.magic &&
			k_uptime_get() < window_deadline, response);
		atomic_set(&has_settings, core.current.magic != 0);
		atomic_set(&reset_pending, core.reset.phase == KR_RESETTING);
		atomic_set(&reset_history, core.reset.ownership_epoch != 0);
		atomic_set(&reset_recovery, core.reset.ownership_epoch && !core.current.magic);
		int64_t stored_version = core.current.version;
		bool stop_peer = core.stop_peer;
		k_mutex_unlock(&core_lock);
		if (stop_peer) {
			const struct kr_peer_plan stopped = {0};
			publish_peer_plan(&stopped);
		}
		int result = respond(conn, response);
		struct kr_peer_plan plan;
		k_mutex_lock(&core_lock, K_FOREVER);
		bool activate_peer = kr_peer_after_response(&core, result == 0, &plan);
		k_mutex_unlock(&core_lock);
		if (activate_peer) {
			publish_peer_plan(&plan);
		}
		printk("NUS response bytes=%u result=%d storedVersion=%lld\n",
			(unsigned)strlen(response), result, (long long)stored_version);
		/* A partial response cannot be followed by a new JSON frame. */
		if (result) { bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN); }
		bt_conn_unref(conn);
		k_mutex_lock(&transport_lock, K_FOREVER);
		if (generation == request_generation) { busy = false; kr_frame_reset(&incoming); }
		k_mutex_unlock(&transport_lock);
	}
}

/* Notification backpressure must not stall the lab's scan and matching loop. */
K_THREAD_DEFINE(nus_worker, 6144, process_requests, NULL, NULL, NULL, 7, 0, 0);

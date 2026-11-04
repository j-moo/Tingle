#include "keyring.h"
#include "phone.h"
#include "storage.h"
#include <errno.h>
#include <string.h>
#include <soc.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/hwinfo.h>
#ifdef KR_DIAG_INTERACTIVE_BOOT
#include <zephyr/drivers/uart.h>
#endif
#include <zephyr/init.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/random/random.h>
#include <zephyr/settings/settings.h>

LOG_MODULE_REGISTER(keyring, LOG_LEVEL_INF);

/* Separate UUIDs prevent this lab binary protocol being mistaken for NUS/JSON v1. */
#define LAB_UUID(n) BT_UUID_128_ENCODE(0x8e8c0000U + (n), 0x67a0, 0x4d2f, 0x9c17, 0x120b4c80e106ULL)
static struct bt_uuid_128 service_uuid = BT_UUID_INIT_128(LAB_UUID(1));
static struct bt_uuid_128 rx_uuid = BT_UUID_INIT_128(LAB_UUID(2));
static struct bt_uuid_128 tx_uuid = BT_UUID_INIT_128(LAB_UUID(3));
static const uint8_t radio_uuid[16] = {LAB_UUID(4)};
static struct kr_engine engine;
static struct bt_le_ext_adv *phone_adv;
static struct bt_le_ext_adv *peer_adv;
static struct bt_conn *phone;
static uint32_t connection_generation;
static uint64_t utc_base;
static int64_t uptime_base;
static unsigned adv_cursor;
static int adv_slot = -1;
static bool adv_active;
static atomic_t adv_finished;
static atomic_t radio_dropped;
static atomic_t restart_phone_adv;
static bool subscribed;
static uint8_t rx_frame[KR_FRAME_MAX];
static size_t rx_len;
static bool command_pending;
static bool command_busy;
static uint8_t tx_frame[KR_FRAME_MAX];
static size_t tx_len;
static size_t tx_offset;
static bool tx_inflight;
K_MUTEX_DEFINE(phone_lock);
K_SEM_DEFINE(wake, 0, 1);

struct radio_message {
	uint64_t source;
	uint16_t len;
	bool malformed;
	uint8_t data[KR_WIRE_MAX];
};
K_MSGQ_DEFINE(radio_rx, sizeof(struct radio_message), 4, 8);

struct notification {
	struct bt_gatt_notify_params params;
	uint32_t generation;
	uint16_t len;
	uint8_t bytes[20];
};
K_MEM_SLAB_DEFINE(notifications, ROUND_UP(sizeof(struct notification), 8), 4, 8);

static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);
static const struct gpio_dt_spec boot_red = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec boot_green = GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios);
static struct k_work_delayable led_off;

enum boot_step {
	BOOT_MAIN, BOOT_ID, BOOT_STORAGE, BOOT_BLUETOOTH, BOOT_SETTINGS,
	BOOT_PHONE_CREATE, BOOT_PHONE_DATA, BOOT_PHONE_START, BOOT_PEER_CREATE,
	BOOT_SCAN, BOOT_READY,
};
static const char *const boot_names[] = {
	"main", "hardware-id", "storage", "bt_enable", "settings_load",
	"phone-adv-create", "phone-adv-data", "phone-adv-start", "peer-adv-create",
	"scan-start", "radio-ready",
};
static atomic_t boot_step;
static atomic_t boot_errno;
static struct k_work_delayable boot_report;

static int boot_led_init(void)
{
	/* Visible before flash/USB initialization, even if main is never reached. */
	if (gpio_is_ready_dt(&boot_red) && gpio_is_ready_dt(&boot_green)) {
		(void)gpio_pin_configure_dt(&boot_red, GPIO_OUTPUT_ACTIVE);
		(void)gpio_pin_configure_dt(&boot_green, GPIO_OUTPUT_INACTIVE);
	}
	return 0;
}

SYS_INIT(boot_led_init, POST_KERNEL, 40);

static void report_boot(struct k_work *work)
{
	ARG_UNUSED(work);
	LOG_INF("BOOT status: stage=%s error=%ld", boot_names[atomic_get(&boot_step)],
		(long)atomic_get(&boot_errno));
	/* Diagnostic reporting only: never delays matching or synchronization queues. */
	(void)k_work_reschedule(&boot_report, K_SECONDS(5));
}

static void boot_progress(enum boot_step step)
{
	atomic_set(&boot_step, step);
	LOG_INF("BOOT: %s", boot_names[step]);
#ifdef KR_DIAG_INTERACTIVE_BOOT
	const struct device *console = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
	if (step == BOOT_BLUETOOTH || step == BOOT_SETTINGS) {
		printk("DIAG CPU: primask=%lx basepri=%lx vtor=%lx icsr=%lx\n",
			(unsigned long)__get_PRIMASK(), (unsigned long)__get_BASEPRI(),
			(unsigned long)SCB->VTOR, (unsigned long)SCB->ICSR);
		printk("DIAG RTC0: count=%lx inten=%lx evten=%lx tick=%lx overflow=%lx cc=%lx,%lx,%lx\n",
			(unsigned long)NRF_RTC0->COUNTER, (unsigned long)NRF_RTC0->INTENSET,
			(unsigned long)NRF_RTC0->EVTENSET, (unsigned long)NRF_RTC0->EVENTS_TICK,
			(unsigned long)NRF_RTC0->EVENTS_OVRFLW,
			(unsigned long)NRF_RTC0->EVENTS_COMPARE[0],
			(unsigned long)NRF_RTC0->EVENTS_COMPARE[1],
			(unsigned long)NRF_RTC0->EVENTS_COMPARE[2]);
		printk("DIAG RTC1: count=%lx inten=%lx cc0=%lx LF=%lx HF=%lx\n",
			(unsigned long)NRF_RTC1->COUNTER, (unsigned long)NRF_RTC1->INTENSET,
			(unsigned long)NRF_RTC1->CC[0], (unsigned long)NRF_CLOCK->LFCLKSTAT,
			(unsigned long)NRF_CLOCK->HFCLKSTAT);
	}
	printk("DIAG WAIT: %s; send c to continue\n", boot_names[step]);
	unsigned char command;
	while (uart_poll_in(console, &command) || command != 'c') {
		k_msleep(10); /* Only this explicitly selected diagnostic build waits. */
	}
#endif
}

static void turn_led_off(struct k_work *work)
{
	ARG_UNUSED(work);
	(void)gpio_pin_set_dt(&led, 0);
}

static void feedback(void *ctx, const uint8_t id[16])
{
	ARG_UNUSED(ctx);
	ARG_UNUSED(id);
	/* Board LED is verified by the board DTS. External motor wiring is not guessed. */
	(void)gpio_pin_set_dt(&led, 1);
	(void)k_work_reschedule(&led_off, K_SECONDS(30));
	LOG_INF("Local test encounter persisted; feedback claimed");
}

static void random_bytes(void *ctx, uint8_t *out, size_t len)
{
	ARG_UNUSED(ctx);
	if (sys_csrand_get(out, len)) {
		LOG_ERR("Secure random source failed");
		k_panic();
	}
}

static uint64_t now_utc(void)
{
	return utc_base ? utc_base + (uint64_t)(k_uptime_get() - uptime_base) / 1000 : 0;
}

static void ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	k_mutex_lock(&phone_lock, K_FOREVER);
	subscribed = value == BT_GATT_CCC_NOTIFY;
	k_mutex_unlock(&phone_lock);
	k_sem_give(&wake);
}

static ssize_t receive_phone(struct bt_conn *conn, const struct bt_gatt_attr *attr,
	const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	ARG_UNUSED(attr);
	if (offset || (flags & BT_GATT_WRITE_FLAG_PREPARE) || !len) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}
	k_mutex_lock(&phone_lock, K_FOREVER);
	if (conn != phone || !subscribed || command_busy || tx_len || rx_len + len > sizeof(rx_frame)) {
		k_mutex_unlock(&phone_lock);
		return BT_GATT_ERR(BT_ATT_ERR_INSUFFICIENT_RESOURCES);
	}
	memcpy(rx_frame + rx_len, buf, len);
	rx_len += len;
	if (rx_len >= 10) {
		size_t total = (size_t)kr_get16(rx_frame + 8) + 14;
		if (rx_frame[0] != 'K' || rx_frame[1] != 'P' || rx_frame[2] != KR_VERSION ||
			total > sizeof(rx_frame) || rx_len > total) {
			rx_len = 0;
			k_mutex_unlock(&phone_lock);
			return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
		}
		if (rx_len == total) {
			command_pending = true;
			command_busy = true;
			k_sem_give(&wake);
		}
	}
	k_mutex_unlock(&phone_lock);
	return len;
}

BT_GATT_SERVICE_DEFINE(lab_service,
	BT_GATT_PRIMARY_SERVICE(&service_uuid),
	BT_GATT_CHARACTERISTIC(&rx_uuid.uuid, BT_GATT_CHRC_WRITE,
		BT_GATT_PERM_WRITE_ENCRYPT, NULL, receive_phone, NULL),
	BT_GATT_CHARACTERISTIC(&tx_uuid.uuid, BT_GATT_CHRC_NOTIFY,
		BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT)
);

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		atomic_set(&restart_phone_adv, 1);
		k_sem_give(&wake);
		return;
	}
	k_mutex_lock(&phone_lock, K_FOREVER);
	phone = bt_conn_ref(conn);
	++connection_generation;
	k_mutex_unlock(&phone_lock);
	int result = bt_conn_set_security(conn, BT_SECURITY_L2);
	if (result) {
		LOG_ERR("Link encryption request failed: %d", result);
		(void)bt_conn_disconnect(conn, BT_HCI_ERR_AUTH_FAIL);
	}
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(reason);
	k_mutex_lock(&phone_lock, K_FOREVER);
	if (phone == conn) {
		bt_conn_unref(phone);
		phone = NULL;
		++connection_generation;
		rx_len = 0;
		tx_len = 0;
		tx_offset = 0;
		tx_inflight = false;
		command_pending = false;
		command_busy = false;
		subscribed = false;
	}
	k_mutex_unlock(&phone_lock);
	atomic_set(&restart_phone_adv, 1);
	k_sem_give(&wake);
}

static void connection_recycled(void)
{
	/* A disconnected callback can precede the controller returning its connection slot. */
	atomic_set(&restart_phone_adv, 1);
	k_sem_give(&wake);
}

BT_CONN_CB_DEFINE(connection_callbacks) = {
	.connected = connected,
	.disconnected = disconnected,
	.recycled = connection_recycled,
};

static void notified(struct bt_conn *conn, void *user_data)
{
	struct notification *note = user_data;
	k_mutex_lock(&phone_lock, K_FOREVER);
	if (conn == phone && note->generation == connection_generation) {
		tx_offset += note->len;
		tx_inflight = false;
		if (tx_offset == tx_len) {
			tx_len = 0;
			tx_offset = 0;
			command_busy = false;
		}
	}
	k_mutex_unlock(&phone_lock);
	k_mem_slab_free(&notifications, note);
	k_sem_give(&wake);
}

static void send_phone(void)
{
	k_mutex_lock(&phone_lock, K_FOREVER);
	if (!phone || !subscribed || !tx_len || tx_inflight) {
		k_mutex_unlock(&phone_lock);
		return;
	}
	struct notification *note;
	if (k_mem_slab_alloc(&notifications, (void **)&note, K_NO_WAIT)) {
		k_mutex_unlock(&phone_lock);
		return;
	}
	memset(note, 0, sizeof(*note));
	note->len = (uint16_t)MIN((size_t)20, tx_len - tx_offset);
	note->generation = connection_generation;
	memcpy(note->bytes, tx_frame + tx_offset, note->len);
	note->params.attr = &lab_service.attrs[4];
	note->params.data = note->bytes;
	note->params.len = note->len;
	note->params.func = notified;
	note->params.user_data = note;
	tx_inflight = true;
	struct bt_conn *conn = bt_conn_ref(phone);
	k_mutex_unlock(&phone_lock);
	int result = bt_gatt_notify_cb(conn, &note->params);
	if (result) {
		LOG_WRN("Notify failed: %d; records remain durable", result);
		k_mem_slab_free(&notifications, note);
		(void)bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
	}
	bt_conn_unref(conn);
}

static void handle_phone(void)
{
	static uint8_t command[KR_FRAME_MAX];
	static uint8_t response[KR_FRAME_MAX];
	k_mutex_lock(&phone_lock, K_FOREVER);
	if (!command_pending || !phone) {
		k_mutex_unlock(&phone_lock);
		return;
	}
	size_t len = rx_len;
	memcpy(command, rx_frame, len);
	rx_len = 0;
	command_pending = false;
	uint32_t generation = connection_generation;
	k_mutex_unlock(&phone_lock);
	uint64_t new_utc;
	size_t result = kr_phone_process(&engine, command, len, response, &new_utc);
	if (new_utc) {
		utc_base = new_utc;
		uptime_base = k_uptime_get();
	}
	k_mutex_lock(&phone_lock, K_FOREVER);
	if (generation == connection_generation && phone) {
		if (result) {
			memcpy(tx_frame, response, result);
			tx_len = result;
			tx_offset = 0;
		} else {
			command_busy = false;
		}
	}
	k_mutex_unlock(&phone_lock);
}

struct assembly {
	struct radio_message *message;
	uint32_t crc;
	uint16_t total;
	bool found;
};

static bool parse_ad(struct bt_data *data, void *user_data)
{
	struct assembly *a = user_data;
	if (data->type != BT_DATA_SVC_DATA128 || data->data_len < 16 ||
		memcmp(data->data, radio_uuid, 16)) {
		return true;
	}
	a->found = true;
	if (data->data_len < 24) {
		a->message->malformed = true;
		return false;
	}
	uint32_t crc = kr_get32(data->data + 16);
	uint16_t total = kr_get16(data->data + 20);
	uint16_t offset = kr_get16(data->data + 22);
	uint16_t chunk = data->data_len - 24;
	if (!a->message->len) {
		a->total = total;
		a->crc = crc;
	}
	if (!total || total > KR_WIRE_MAX || total != a->total || crc != a->crc ||
		offset != a->message->len || (size_t)offset + chunk > total) {
		a->message->malformed = true;
		return false;
	}
	memcpy(a->message->data + offset, data->data + 24, chunk);
	a->message->len += chunk;
	return true;
}

static void scan_received(const struct bt_le_scan_recv_info *info,
	struct net_buf_simple *buffer)
{
	/* Zephyr serializes these callbacks on the BT RX workqueue. The message is
	 * copied into radio_rx before reuse; avoid a 1.2 KiB callback stack frame.
	 */
	static struct radio_message message;
	memset(&message, 0, sizeof(message));
	struct assembly a = {.message = &message};
	message.source = kr_crc32(info->addr->a.val, 6);
	bt_data_parse(buffer, parse_ad, &a);
	if (!a.found) {
		return;
	}
	message.malformed |= message.len != a.total ||
		kr_crc32(message.data, message.len) != a.crc;
	if (k_msgq_put(&radio_rx, &message, K_NO_WAIT)) {
		atomic_inc(&radio_dropped);
	}
	k_sem_give(&wake);
}

static struct bt_le_scan_cb scan_callbacks = {.recv = scan_received};

static void advertising_sent(struct bt_le_ext_adv *adv,
	struct bt_le_ext_adv_sent_info *info)
{
	ARG_UNUSED(adv);
	ARG_UNUSED(info);
	atomic_set(&adv_finished, 1);
	k_sem_give(&wake);
}

static const struct bt_le_ext_adv_cb adv_callbacks = {.sent = advertising_sent};

static int advertise_next(void)
{
	static uint8_t packet[KR_WIRE_MAX];
	static uint8_t chunks[6][254];
	static struct bt_data fields[6];
	if (atomic_cas(&adv_finished, 1, 0)) {
		adv_active = false;
		kr_sent(&engine, adv_slot);
	}
	if (adv_active || !engine.ready) {
		return 0;
	}
	size_t len = kr_advertisement(&engine, &adv_cursor, packet, &adv_slot);
	if (!len) {
		return 0;
	}
	unsigned count = 0;
	uint32_t crc = kr_crc32(packet, len);
	for (size_t offset = 0; offset < len; offset += 230) {
		size_t n = MIN((size_t)230, len - offset);
		memcpy(chunks[count], radio_uuid, 16);
		kr_put32(chunks[count] + 16, crc);
		kr_put16(chunks[count] + 20, (uint16_t)len);
		kr_put16(chunks[count] + 22, (uint16_t)offset);
		memcpy(chunks[count] + 24, packet + offset, n);
		fields[count] = (struct bt_data)BT_DATA(BT_DATA_SVC_DATA128, chunks[count], n + 24);
		++count;
	}
	int result = bt_le_ext_adv_set_data(peer_adv, fields, count, NULL, 0);
	if (result) {
		return result;
	}
	struct bt_le_ext_adv_start_param start = {.num_events = 1};
	result = bt_le_ext_adv_start(peer_adv, &start);
	if (!result) {
		adv_active = true;
	}
	return result;
}

static int radio_start(void)
{
	boot_progress(BOOT_BLUETOOTH);
	int result = bt_enable(NULL);
	if (result) {
		return result;
	}
	boot_progress(BOOT_SETTINGS);
	result = settings_load();
	if (result) {
		return result;
	}
	struct bt_le_adv_param connectable = {
		.id = BT_ID_DEFAULT, .sid = 0, .options = BT_LE_ADV_OPT_CONN,
		.interval_min = BT_GAP_ADV_FAST_INT_MIN_2,
		.interval_max = BT_GAP_ADV_FAST_INT_MAX_2,
	};
	boot_progress(BOOT_PHONE_CREATE);
	result = bt_le_ext_adv_create(&connectable, NULL, &phone_adv);
	if (result) {
		return result;
	}
	const struct bt_data ad[] = {
		BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
		BT_DATA_BYTES(BT_DATA_UUID128_ALL, LAB_UUID(1)),
	};
	const struct bt_data scan_response[] = {
		BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME, sizeof(CONFIG_BT_DEVICE_NAME) - 1),
	};
	boot_progress(BOOT_PHONE_DATA);
	result = bt_le_ext_adv_set_data(phone_adv, ad, ARRAY_SIZE(ad),
		scan_response, ARRAY_SIZE(scan_response));
	if (result) {
		return result;
	}
	boot_progress(BOOT_PHONE_START);
	result = bt_le_ext_adv_start(phone_adv, BT_LE_EXT_ADV_START_DEFAULT);
	if (result) {
		return result;
	}
	struct bt_le_adv_param extended = {
		.id = BT_ID_DEFAULT, .sid = 1,
		.options = BT_LE_ADV_OPT_EXT_ADV | BT_LE_ADV_OPT_NO_2M,
		/* Radio scheduling interval, not a delay applied to any application queue. */
		.interval_min = BT_GAP_ADV_FAST_INT_MIN_2,
		.interval_max = BT_GAP_ADV_FAST_INT_MAX_2,
	};
	boot_progress(BOOT_PEER_CREATE);
	result = bt_le_ext_adv_create(&extended, &adv_callbacks, &peer_adv);
	if (result) {
		return result;
	}
	bt_le_scan_cb_register(&scan_callbacks);
	struct bt_le_scan_param scan = {
		.type = BT_LE_SCAN_TYPE_PASSIVE, .options = BT_LE_SCAN_OPT_NONE,
		.interval = BT_GAP_SCAN_FAST_INTERVAL, .window = BT_GAP_SCAN_FAST_WINDOW,
	};
	boot_progress(BOOT_SCAN);
	return bt_le_scan_start(&scan, NULL);
}

int main(void)
{
	k_work_init_delayable(&boot_report, report_boot);
	(void)k_work_schedule(&boot_report, K_NO_WAIT);
	LOG_INF("Bootloader address: 0x%08x", NRF_UICR->NRFFW[0]);
	boot_progress(BOOT_ID);
	uint8_t device_bytes[8];
	if (hwinfo_get_device_id(device_bytes, sizeof(device_bytes)) != sizeof(device_bytes)) {
		atomic_set(&boot_errno, -ENODEV);
		LOG_ERR("No hardware identity");
		return 0;
	}
	uint64_t device_id = kr_get64(device_bytes) & INT64_MAX;
	struct kr_io io = {
		.config = kr_storage_config, .record = kr_storage_record,
		.suppression = kr_storage_suppression, .random = random_bytes,
		.feedback = feedback, .calculate = kr_always_match,
	};
	kr_init(&engine, device_id, io);
	boot_progress(BOOT_STORAGE);
	int result = kr_storage_open(&engine);
	if (result) {
		engine.storage_error = true;
		atomic_set(&boot_errno, result);
		LOG_ERR("Storage unavailable: %d; preserved, no new matching", result);
	}
	if (!gpio_is_ready_dt(&led) || gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE)) {
		atomic_set(&boot_errno, -ENODEV);
		LOG_ERR("Board LED unavailable");
		return 0;
	}
	k_work_init_delayable(&led_off, turn_led_off);
	result = radio_start();
	if (result) {
		atomic_set(&boot_errno, result);
		LOG_ERR("BLE initialization failed: %d", result);
		return 0;
	}
	boot_progress(BOOT_READY);
	(void)gpio_pin_set_dt(&boot_red, engine.storage_error ? 1 : 0);
	(void)gpio_pin_set_dt(&boot_green, 1);
	LOG_INF("LAB ONLY: binary sync, extended advertising, always-match; awaiting time sync");
	k_sem_give(&wake);
	for (;;) {
		k_sem_take(&wake, K_FOREVER);
		handle_phone();
		struct radio_message message;
		/* Bound a dispatch pass so continuous scanning cannot starve the phone. */
		for (unsigned n = 0; n < 4 && !k_msgq_get(&radio_rx, &message, K_NO_WAIT); ++n) {
			if (message.malformed) {
				(void)kr_radio_error(&engine, message.source, message.data,
					message.len, KR_BAD_DATA, now_utc());
			} else {
				(void)kr_receive(&engine, message.data, message.len, message.source, now_utc());
			}
		}
		atomic_val_t dropped = atomic_set(&radio_dropped, 0);
		if (dropped) {
			engine.overflow += (uint32_t)dropped;
			uint8_t count[4];
			kr_put32(count, (uint32_t)dropped);
			(void)kr_radio_error(&engine, device_id, count, sizeof(count),
				KR_RADIO_OVERFLOW, now_utc());
		}
		kr_drain(&engine);
		if (atomic_cas(&restart_phone_adv, 1, 0)) {
			result = bt_le_ext_adv_start(phone_adv, BT_LE_EXT_ADV_START_DEFAULT);
			if (result && result != -EALREADY) {
				LOG_ERR("Phone advertising restart failed: %d", result);
			}
		}
		result = advertise_next();
		if (result) {
			LOG_ERR("Extended advertising failed: %d", result);
			engine.ready = false;
		}
		send_phone();
		if (k_msgq_num_used_get(&radio_rx)) {
			k_sem_give(&wake);
		}
	}
	return 0;
}

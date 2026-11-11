#include "keyring.h"
#include "carrier.h"
#include "board.h"
#include "nus.h"
#include "phone.h"
#include "storage.h"
#include "peer.h"
#include "service.h"
#include "monocypher.h"
#include <errno.h>
#include <string.h>
#include <soc.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/init.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/pm/device.h>
#include <zephyr/random/random.h>
#include <zephyr/settings/settings.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/util.h>

LOG_MODULE_REGISTER(keyring, LOG_LEVEL_INF);

/* Separate UUIDs prevent this lab binary protocol being mistaken for NUS/JSON v1. */
#define LAB_UUID(n) BT_UUID_128_ENCODE(0x8e8c0000U + (n), 0x67a0, 0x4d2f, 0x9c17, 0x120b4c80e106ULL)
#define LAB_CONTROL_BODY_BYTES 65
static struct bt_uuid_128 service_uuid = BT_UUID_INIT_128(LAB_UUID(1));
static struct bt_uuid_128 rx_uuid = BT_UUID_INIT_128(LAB_UUID(2));
static struct bt_uuid_128 tx_uuid = BT_UUID_INIT_128(LAB_UUID(3));
static const uint8_t radio_uuid[16] = {LAB_UUID(4)};
static const uint8_t peer_uuid[16] = {LAB_UUID(7)};
static struct kr_peer_runtime peers;
static atomic_t peer_exchange_enabled;
static atomic_t settings_generation;
static struct k_spinlock peer_filter_lock;
static uint8_t peer_filter[32];
static struct kr_engine engine;
static uint64_t device_id;
static struct bt_le_ext_adv *phone_adv;
static struct bt_le_ext_adv *peer_adv;
static bool peer_scanning;
static struct bt_conn *phone;
static uint32_t connection_generation;
static uint64_t utc_base;
static int64_t uptime_base;
static unsigned adv_cursor, adv_next_cursor;
static int adv_slot = -1;
static uint8_t adv_event[16];
static uint32_t adv_record_sequence;
static bool adv_active;
enum adv_completion { ADV_PENDING, ADV_UNSENT, ADV_SENT };
static atomic_t adv_finished;
static atomic_t radio_dropped;
static atomic_t restart_phone_adv;
static atomic_t lab_enabled;
static atomic_t service_scan_enabled;
static atomic_t service_discoveries;
static atomic_t peer_authenticated_count, peer_exchange_count, peer_rejected_count;
static bool subscribed;
static uint8_t rx_frame[KR_BINARY_FRAME_MAX];
static size_t rx_len;
static bool command_pending;
static bool command_busy;
static uint8_t tx_frame[KR_BINARY_FRAME_MAX];
static size_t tx_len;
static size_t tx_offset;
static bool tx_inflight;
K_MUTEX_DEFINE(phone_lock);
K_MUTEX_DEFINE(owner_data_lock);
K_SEM_DEFINE(wake, 0, 1);

void kr_runtime_owner_lock(void) { k_mutex_lock(&owner_data_lock, K_FOREVER); }
void kr_runtime_owner_unlock(void) { k_mutex_unlock(&owner_data_lock); }

int kr_runtime_pending_records(void)
{
	if (engine.storage_error) { return -EIO; }
	return (int)kr_pending(&engine);
}

void kr_runtime_pause_matching(void)
{
	atomic_inc(&settings_generation);
	atomic_clear(&lab_enabled);
	atomic_clear(&service_scan_enabled);
	atomic_clear(&peer_exchange_enabled);
	kr_runtime_stop_feedback();
	k_sem_give(&wake);
}
void kr_runtime_reset_block(void) { kr_runtime_pause_matching(); }
uint32_t kr_runtime_generation(void) { return (uint32_t)atomic_get(&settings_generation); }

int kr_runtime_clear_owner_data(void)
{
	kr_peer_stop(&peers);
	kr_nus_clear_application();
	int result = kr_storage_clear_owner(&engine);
	if (!result) {
		k_mutex_lock(&phone_lock, K_FOREVER);
		memset(rx_frame, 0, sizeof(rx_frame));
		memset(tx_frame, 0, sizeof(tx_frame));
		rx_len = tx_len = tx_offset = 0;
		command_pending = command_busy = tx_inflight = false;
		k_mutex_unlock(&phone_lock);
		utc_base = 0;
		uptime_base = 0;
	}
	return result;
}

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
static void turn_led_off(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(led_off, turn_led_off);
static struct {
	uint64_t device;
	uint32_t revision;
	uint8_t scope[16];
	int64_t until_ms;
} feedback_peer;

enum boot_step {
	BOOT_MAIN, BOOT_ID, BOOT_STORAGE, BOOT_BLUETOOTH, BOOT_SETTINGS,
	BOOT_PHONE_CREATE, BOOT_PHONE_DATA, BOOT_PHONE_START, BOOT_READY,
};
static const char *const boot_names[] = {
	"main", "hardware-id", "storage", "bt_enable", "settings_load",
	"phone-adv-create", "phone-adv-data", "phone-adv-start", "radio-ready",
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
	if (atomic_get(&service_scan_enabled)) {
		if (atomic_get(&peer_exchange_enabled)) {
			LOG_INF("Peer proof=%ld exchange=%ld rejected=%ld", (long)atomic_get(&peer_authenticated_count),
				(long)atomic_get(&peer_exchange_count), (long)atomic_get(&peer_rejected_count));
		} else { LOG_INF("Service discovery RX=%ld", (long)atomic_set(&service_discoveries, 0)); }
	}
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
void kr_runtime_stop_feedback(void)
{
	kr_carrier_feedback_stop();
	(void)k_work_cancel_delayable(&led_off);
	if (gpio_is_ready_dt(&led)) { (void)gpio_pin_set_dt(&led, 0); }
}

static void feedback(void *ctx, const struct kr_record *record, const struct kr_profile *peer)
{
	ARG_UNUSED(ctx);
	if (atomic_get(&lab_enabled)) {
		(void)gpio_pin_set_dt(&led, 1);
		(void)k_work_reschedule(&led_off, K_MSEC(KR_FEEDBACK_LED_MS));
		LOG_INF("Local test encounter persisted; feedback claimed");
		return;
	}
	const struct kr_peer_verified *remote = peers.input_peer;
	if (!atomic_get(&peer_exchange_enabled) || !remote || remote->device != record->peer ||
		!kr_nus_feedback_presence_seconds()) { return; }
	struct kr_feedback_options options = kr_feedback_options(record->id, &record->own, peer);
	if (!kr_carrier_feedback_start(options)) { return; }
	feedback_peer.device = remote->device;
	feedback_peer.revision = remote->revision;
	memcpy(feedback_peer.scope, remote->scope, sizeof(feedback_peer.scope));
	feedback_peer.until_ms = k_uptime_get() + KR_FEEDBACK_LED_MS;
}

/* Called under the same owner lock as peer session and encounter updates. */
static void check_feedback_presence(void)
{
	if (!feedback_peer.device) { return; }
	if (k_uptime_get() >= feedback_peer.until_ms) {
		feedback_peer.device = 0;
		return;
	}
	uint64_t now = kr_nus_application_time();
	unsigned gap = kr_nus_feedback_presence_seconds();
	for (unsigned i = 0; gap && i < KR_PEERS; ++i) {
		const struct kr_peer_session *session = &peers.sessions[i];
		const struct kr_peer_verified *remote = &session->remote;
		if (session->authenticated && remote->device == feedback_peer.device &&
			remote->revision == feedback_peer.revision &&
			!memcmp(remote->scope, feedback_peer.scope, sizeof(feedback_peer.scope)) &&
			remote->valid_until > now && session->last_seen <= now && now - session->last_seen <= gap) {
			return;
		}
	}
	feedback_peer.device = 0;
	kr_carrier_feedback_stop();
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
	uint64_t trusted = kr_nus_application_time(); if (trusted) { return trusted; }
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
	if (!atomic_get(&lab_enabled)) {
		return BT_GATT_ERR(BT_ATT_ERR_AUTHORIZATION);
	}
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
	/* The lab client requests pairing before using its encrypted service.
	 * NUS can use the same link without triggering a pairing prompt. */
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
	static uint8_t command[KR_BINARY_FRAME_MAX];
	static uint8_t response[KR_BINARY_FRAME_MAX];
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

static bool count_service_discovery(struct bt_data *data, void *user_data)
{
	ARG_UNUSED(user_data);
	static const uint8_t uuid[] = {KR_DEV_DISCOVERY_UUID};
	if (data->type == BT_DATA_SVC_DATA128 && data->data_len == KR_DEV_DISCOVERY_BYTES &&
		!memcmp(data->data, uuid, sizeof(uuid))) {
		atomic_inc(&service_discoveries);
		return false;
	}
	return true;
}

static bool parse_ad(struct bt_data *data, void *user_data)
{
	struct assembly *a = user_data;
	if (data->type != BT_DATA_SVC_DATA128 || data->data_len < 16 ||
		memcmp(data->data, atomic_get(&peer_exchange_enabled) ? peer_uuid : radio_uuid, 16)) {
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
	if (!atomic_get(&lab_enabled) && !atomic_get(&peer_exchange_enabled)) {
		if (atomic_get(&service_scan_enabled)) {
			bt_data_parse(buffer, count_service_discovery, NULL);
		}
		return;
	}
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
	if (atomic_get(&peer_exchange_enabled) && !message.malformed && message.len >= KR_PEER_HEADER &&
		!memcmp(message.data, "PX\1", 3) && message.data[3] != 1) {
		k_spinlock_key_t key = k_spin_lock(&peer_filter_lock);
		bool ours = !memcmp(message.data + 36, peer_filter, sizeof(peer_filter));
		k_spin_unlock(&peer_filter_lock, key);
		if (!ours) { return; } /* Other pairs never consume this board's RX queue. */
	}
	/* In a crowd, other pairs' valid control packets must not consume RX slots. */
	if (atomic_get(&lab_enabled) && !message.malformed && message.len == LAB_CONTROL_BODY_BYTES + 10 &&
		message.data[3] == 2 && kr_packet_valid(message.data, message.len) &&
		kr_get64(message.data + 14) != device_id) {
		return;
	}
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
	atomic_set(&adv_finished, info->num_sent ? ADV_SENT : ADV_UNSENT);
	k_sem_give(&wake);
}

static const struct bt_le_ext_adv_cb adv_callbacks = {.sent = advertising_sent};

static int advertise_next(void)
{
	static uint8_t packet[KR_WIRE_MAX];
	static uint8_t chunks[6][254];
	static struct bt_data fields[6];
	atomic_val_t completed = atomic_set(&adv_finished, ADV_PENDING);
	if (completed != ADV_PENDING) {
		adv_active = false;
		if (completed == ADV_SENT) {
			adv_cursor = adv_next_cursor;
			(void)kr_sent_guarded(&engine, adv_slot, adv_record_sequence, adv_event);
		}
	}
	if (adv_active || !engine.ready) {
		return 0;
	}
	unsigned next_cursor = adv_cursor;
	int next_slot;
	size_t len = atomic_get(&peer_exchange_enabled) ?
		kr_peer_advertisement(&peers, &engine, packet, &next_slot) :
		kr_advertisement(&engine, &next_cursor, packet, &next_slot);
	if (!len) {
		return 0;
	}
	unsigned count = 0;
	uint32_t crc = kr_crc32(packet, len);
	for (size_t offset = 0; offset < len; offset += 230) {
		size_t n = MIN((size_t)230, len - offset);
		memcpy(chunks[count], atomic_get(&peer_exchange_enabled) ? peer_uuid : radio_uuid, 16);
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
		adv_next_cursor = next_cursor;
		adv_slot = next_slot;
		if (adv_slot >= 0 && adv_slot < KR_RECORDS) {
			memcpy(adv_event, engine.records[adv_slot].id, 16); adv_record_sequence = engine.records[adv_slot].sequence;
		} else if (adv_slot >= KR_RECORDS && adv_slot < KR_RECORDS + KR_SUPPRESSIONS) {
			memcpy(adv_event, engine.suppressed[adv_slot - KR_RECORDS].id, 16);
		}
		adv_active = true;
	}
	return result;
}

static int phone_advertising_data(bool lab)
{
	const struct bt_data ad[] = {
		BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
		BT_DATA_BYTES(BT_DATA_UUID128_ALL, KR_NUS_UUID(0x6e400001)),
	};
	/* A legacy scan response has 31 bytes. The lab name and UUID fit together. */
	const struct bt_data lab_scan_response[] = {
		BT_DATA(BT_DATA_NAME_COMPLETE, "Tingle-Lab", sizeof("Tingle-Lab") - 1),
		BT_DATA_BYTES(BT_DATA_UUID128_ALL, LAB_UUID(1)),
	};
	return bt_le_ext_adv_set_data(phone_adv, ad, ARRAY_SIZE(ad),
		lab ? lab_scan_response : NULL, lab ? ARRAY_SIZE(lab_scan_response) : 0);
}

static int sync_phone_advertising(void)
{
	if (!atomic_get(&lab_enabled) && !kr_nus_can_advertise()) {
		return bt_le_ext_adv_stop(phone_adv);
	}
	int result = bt_le_ext_adv_start(phone_adv, BT_LE_EXT_ADV_START_DEFAULT);
	return result == -EALREADY ? 0 : result;
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
	boot_progress(BOOT_PHONE_DATA);
	result = phone_advertising_data(false);
	if (result) {
		return result;
	}
	boot_progress(BOOT_PHONE_START);
	kr_nus_start_window();
	return sync_phone_advertising();
}

static int prepare_peer_adv(void)
{
	if (peer_adv) {
		return 0;
	}
	struct bt_le_adv_param extended = {
		.id = BT_ID_DEFAULT, .sid = 1,
		.options = BT_LE_ADV_OPT_EXT_ADV | BT_LE_ADV_OPT_NO_2M,
		/* Radio scheduling interval, not a delay applied to any application queue. */
		.interval_min = BT_GAP_ADV_FAST_INT_MIN_2,
		.interval_max = BT_GAP_ADV_FAST_INT_MAX_2,
	};
	return bt_le_ext_adv_create(&extended, &adv_callbacks, &peer_adv);
}

static int set_peer_scan(bool enable)
{
	if (enable == peer_scanning) {
		return 0;
	}
	struct bt_le_scan_param scan = {
		.type = BT_LE_SCAN_TYPE_PASSIVE, .options = BT_LE_SCAN_OPT_NONE,
		.interval = BT_GAP_SCAN_FAST_INTERVAL, .window = BT_GAP_SCAN_FAST_WINDOW,
	};
	int result = enable ? bt_le_scan_start(&scan, NULL) : bt_le_scan_stop();
	if (!result) {
		peer_scanning = enable;
	}
	return result;
}

static int apply_service_peer(void *ctx, const struct kr_peer_plan *plan)
{
	ARG_UNUSED(ctx);
	int result = kr_nus_apply_application(plan, &peers);
	if (result) { return result; }
	if (plan->peer.valid && peers.active && !memcmp(peers.own.public_key, plan->peer.public_key, 32)) {
		/* Phone advertising/cache retries must not reset a live crypto session. */
		kr_runtime_owner_lock();
		if (plan->generation != kr_runtime_generation()) { kr_runtime_owner_unlock(); return -EAGAIN; }
		atomic_set(&peer_exchange_enabled, 1); kr_runtime_owner_unlock(); return set_peer_scan(true);
	}
	atomic_clear(&peer_exchange_enabled);
	result = peer_adv ? bt_le_ext_adv_stop(peer_adv) : 0;
	if (result) {
		return result;
	}
	adv_active = false; adv_slot = -1; atomic_set(&adv_finished, ADV_PENDING);
	kr_runtime_owner_lock();
	kr_peer_stop(&peers); engine.ready = false;
	kr_runtime_owner_unlock();
	k_msgq_purge(&radio_rx);
	if (!plan->length) {
		return set_peer_scan(false);
	}
	result = prepare_peer_adv();
	if (result) {
		return result;
	}
	if (plan->peer.valid) {
#ifdef KR_DEV_SERVER_KEY_HEX
		uint8_t public_key[32];
		if (hex2bin(KR_DEV_SERVER_KEY_HEX, 64, public_key, sizeof(public_key)) != sizeof(public_key)) { return -EINVAL; }
		kr_runtime_owner_lock();
		result = kr_peer_start(&peers, &plan->peer, public_key, &engine);
		if (!result) { kr_nus_bind_application(&peers); }
		kr_runtime_owner_unlock();
		if (result) { return -EACCES; }
		utc_base = (uint64_t)plan->verified_time; uptime_base = k_uptime_get();
		k_spinlock_key_t key = k_spin_lock(&peer_filter_lock);
		memcpy(peer_filter, peers.own.public_key, sizeof(peer_filter));
		k_spin_unlock(&peer_filter_lock, key);
		kr_runtime_owner_lock();
		if (plan->generation != kr_runtime_generation()) { kr_runtime_owner_unlock(); return -EAGAIN; }
		atomic_set(&peer_exchange_enabled, 1); kr_runtime_owner_unlock();
		result = set_peer_scan(true);
		if (result) {
			atomic_clear(&peer_exchange_enabled); kr_runtime_owner_lock(); kr_peer_stop(&peers); engine.ready = false;
			kr_runtime_owner_unlock(); return result;
		}
		return 0;
#else
		return -ENOTSUP;
#endif
	}
	const struct bt_data ad = BT_DATA(BT_DATA_SVC_DATA128, plan->service_data, plan->length);
	result = bt_le_ext_adv_set_data(peer_adv, &ad, 1, NULL, 0);
	if (!result) {
		result = set_peer_scan(true);
	}
	if (!result) {
		result = bt_le_ext_adv_start(peer_adv, BT_LE_EXT_ADV_START_DEFAULT);
	}
	return result;
}

static bool service_reset_pending(void *ctx) { ARG_UNUSED(ctx); return kr_nus_reset_pending(); }
static uint32_t service_block_reset(void *ctx)
{
	ARG_UNUSED(ctx);
	kr_runtime_reset_block();
	k_msgq_purge(&radio_rx);
	return kr_runtime_generation();
}
static int service_refresh_phone(void *ctx) { ARG_UNUSED(ctx); return phone_advertising_data(false); }
static const char *service_finish_reset(void *ctx) { ARG_UNUSED(ctx); return kr_nus_finish_reset(); }
static void service_apply_result(void *ctx, const struct kr_peer_plan *plan, int error)
{
	ARG_UNUSED(ctx);
	kr_nus_application_result(plan, error);
}
static const struct kr_service_io service_io = {
	.reset_pending = service_reset_pending, .block_reset = service_block_reset,
	.apply = apply_service_peer, .refresh_phone = service_refresh_phone, .finish_reset = service_finish_reset,
	.apply_result = service_apply_result,
};

static int enable_lab(void)
{
	if (engine.storage_error) {
		return -EIO;
	}
	int result = prepare_peer_adv();
	if (!result) {
		result = set_peer_scan(true);
	}
	if (!result) {
		result = phone_advertising_data(true);
	}
	if (result) {
		(void)set_peer_scan(false);
		return result;
	}
	atomic_set(&lab_enabled, 1);
	atomic_set(&restart_phone_adv, 1);
	return 0;
}

static bool poll_usb_commands(bool allow_lab)
{
	static const char forget[] = "BLE FORGET ";
	static char line[sizeof("BLE FORGET 01234567\r")];
	static size_t length;
	static bool invalid;
	const struct device *console = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
	unsigned char byte;
	for (unsigned i = 0; i < 64 && !uart_poll_in(console, &byte); ++i) {
		if (byte != '\n') {
			if (length == sizeof(line) - 1 || byte == '\0') { invalid = true; }
			if (!invalid) { line[length++] = byte; }
			continue;
		}
		if (length && line[length - 1] == '\r') { --length; }
		line[length] = '\0';
		bool valid = !invalid;
		length = 0;
		invalid = false;
		if (!valid) { continue; }
		if (allow_lab && !strcmp(line, "LAB ENABLE")) { return true; }
		if (!strncmp(line, forget, sizeof(forget) - 1)) {
			const char *argument = line + sizeof(forget) - 1;
			uint8_t token[4];
			uint32_t dtr;
			if (uart_line_ctrl_get(console, UART_LINE_CTRL_DTR, &dtr) || !dtr) { continue; }
			if (strlen(argument) != 8 || hex2bin(argument, 8, token, sizeof(token)) != sizeof(token)) {
				LOG_WRN("Use BLE FORGET followed by the eight-digit token from BLE pairing failure");
				continue;
			}
			int result = bt_le_ext_adv_stop(phone_adv);
			if (!result) {
				result = kr_nus_forget_failed_bond(sys_get_be32(token));
				atomic_set(&restart_phone_adv, 1);
			}
			if (result) {
				LOG_WRN("BLE bond forget failed: %d", result);
			} else {
				LOG_INF("BLE bond forgotten");
			}
		}
	}
	return false;
}

static void check_carrier_off(bool radio_running)
{
	if (!kr_carrier_off_requested()) { return; }
	k_msleep(20); /* Slide-switch debounce only. */
	if (!kr_carrier_off_requested()) { return; }
	kr_runtime_stop_feedback();
	(void)gpio_pin_set_dt(&boot_red, 0);
	(void)gpio_pin_set_dt(&boot_green, 0);
	if (radio_running) {
		kr_nus_quiesce();
		int result = bt_disable();
		if (result) { LOG_WRN("BLE shutdown returned %d", result); }
	}
	const struct device *flash = FIXED_PARTITION_DEVICE(registration_store);
	if (device_is_ready(flash)) {
		int result;
		do {
			result = pm_device_action_run(flash, PM_DEVICE_ACTION_SUSPEND);
			if (result == -EBUSY) { k_msleep(5); }
		} while (result == -EBUSY);
		if (result && result != -EALREADY) { LOG_WRN("Flash standby returned %d", result); }
	}
	kr_carrier_enter_off();
}

int main(void)
{
	int charge_error = kr_board_charge_init();
	if (charge_error) { LOG_ERR("50 mA charge pin unavailable: %d", charge_error); }
	int carrier_error = kr_carrier_init();
	if (carrier_error) { LOG_ERR("Carrier feedback unavailable: %d", carrier_error); }
	check_carrier_off(false);
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
	device_id = kr_get64(device_bytes) & INT64_MAX;
	struct kr_io io = {
		.config = kr_storage_config, .record = kr_storage_record,
		.suppression = kr_storage_suppression, .random = random_bytes,
		.feedback = feedback, .calculate = kr_always_match, .sequence = kr_storage_sequence,
		.synthetic_only = true,
	};
	kr_init(&engine, device_id, io);
	boot_progress(BOOT_STORAGE);
	int result = kr_nus_init(&wake);
	if (result) {
		atomic_set(&boot_errno, result);
		LOG_ERR("Registration storage unavailable: %d", result);
		return 0;
	}
	result = kr_storage_open(&engine);
	if (result) {
		engine.storage_error = true;
		atomic_set(&boot_errno, result);
		LOG_ERR("Storage unavailable: %d; preserved, no new matching", result);
	}
	kr_nus_attach_application(&engine);
	if (!gpio_is_ready_dt(&led) || gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE)) {
		atomic_set(&boot_errno, -ENODEV);
		LOG_ERR("Board LED unavailable");
		return 0;
	}
	bt_le_scan_cb_register(&scan_callbacks);
	result = radio_start();
	if (result) {
		atomic_set(&boot_errno, result);
		LOG_ERR("BLE initialization failed: %d", result);
		return 0;
	}
	boot_progress(BOOT_READY);
	(void)gpio_pin_set_dt(&boot_red, engine.storage_error ? 1 : 0);
	(void)gpio_pin_set_dt(&boot_green, 1);
	LOG_INF("NUS ready, registration window=60s. USB LAB ENABLE starts synthetic matching for this boot");
	bool registration_expired = false;
	bool service_mode = false, service_update = false;
	struct kr_peer_plan service_plan;
	int last_adv_error = 0;
	const char *last_reset_error = NULL;
	for (;;) {
		k_sem_take(&wake, K_MSEC(100));
		check_carrier_off(true);
		kr_nus_poll_console(false);
		if (kr_nus_take_peer_plan(&service_plan)) {
			service_mode = service_update = true;
			atomic_clear(&service_scan_enabled);
			atomic_clear(&peer_exchange_enabled);
			if (atomic_cas(&lab_enabled, 1, 0)) {
				kr_runtime_stop_feedback();
			}
		}
		struct kr_service_progress progress = kr_service_step(&service_plan, &service_update, &service_io);
		if (progress.reset_pending) { service_mode = true; }
		if (progress.applied) {
			atomic_set(&service_scan_enabled, service_plan.length != 0);
			atomic_set(&restart_phone_adv, 1);
			LOG_INF("Service discovery %s", service_plan.length ? "active" : "stopped");
		} else if (progress.radio_error && progress.radio_error != last_adv_error) {
			LOG_WRN("Service discovery update failed: %d; retrying", progress.radio_error);
		}
		if (progress.applied || progress.radio_error) { last_adv_error = progress.radio_error; }
		if (progress.reset_attempted) {
			const char *error = progress.reset_error;
			if (error && (!last_reset_error || strcmp(last_reset_error, error))) {
				LOG_ERR("Reset recovery pending: %s", error);
			}
			last_reset_error = error;
			atomic_set(&restart_phone_adv, 1);
		}
		bool start_lab = poll_usb_commands(!service_mode && kr_nus_lab_allowed() && !atomic_get(&lab_enabled));
		kr_runtime_owner_lock();
		kr_nus_application_tick();
		if (start_lab && kr_nus_lab_allowed()) {
			result = enable_lab();
			if (result) {
				LOG_ERR("Lab activation failed: %d", result);
			} else {
				LOG_INF("LAB ENABLED: binary sync, extended advertising, always-match; awaiting time sync");
			}
		}
		kr_runtime_owner_unlock();
		if (!registration_expired && !kr_nus_can_advertise()) {
			registration_expired = true;
			atomic_set(&restart_phone_adv, 1);
			LOG_INF("Registration window closed; reboot to reopen");
		}
		if (atomic_cas(&restart_phone_adv, 1, 0)) {
			result = sync_phone_advertising();
			if (result && result != -ENOMEM) {
				LOG_ERR("Phone advertising update failed: %d", result);
			}
		}
		if (!atomic_get(&lab_enabled) && !atomic_get(&peer_exchange_enabled)) {
			continue;
		}
		kr_runtime_owner_lock();
		if (!atomic_get(&lab_enabled) && !atomic_get(&peer_exchange_enabled)) {
			kr_runtime_owner_unlock();
			continue;
		}
		if (atomic_get(&lab_enabled)) { handle_phone(); }
		peers.now = now_utc();
		kr_expire_candidates(&engine, now_utc());
		struct radio_message message;
		/* Bound a dispatch pass so continuous scanning cannot starve the phone. */
		for (unsigned n = 0; n < 4 && !k_msgq_get(&radio_rx, &message, K_NO_WAIT); ++n) {
			if (atomic_get(&peer_exchange_enabled)) {
				if (!message.malformed) { (void)kr_peer_receive(&peers, &engine, message.data, message.len, now_utc()); }
				else { ++peers.rejected; }
			} else if (message.malformed) {
				(void)kr_radio_error(&engine, message.source, message.data,
					message.len, KR_BAD_DATA, now_utc());
			} else {
				(void)kr_receive(&engine, message.data, message.len, message.source, now_utc());
			}
		}
		atomic_val_t dropped = atomic_set(&radio_dropped, 0);
		if (dropped) {
			engine.overflow += (uint32_t)dropped;
			if (atomic_get(&peer_exchange_enabled)) { peers.overflow += (uint32_t)dropped; }
			else {
				uint8_t count[4]; kr_put32(count, (uint32_t)dropped);
				(void)kr_radio_error(&engine, device_id, count, sizeof(count), KR_RADIO_OVERFLOW, now_utc());
			}
		}
		kr_drain(&engine);
		check_feedback_presence();
		if (atomic_get(&peer_exchange_enabled)) {
			atomic_set(&peer_authenticated_count, peers.authenticated);
			atomic_set(&peer_exchange_count, peers.exchanged);
			atomic_set(&peer_rejected_count, peers.rejected);
		}
		result = advertise_next();
		if (result && result != last_adv_error) {
			LOG_WRN("Extended advertising start failed: %d; retrying", result);
		}
		last_adv_error = result;
		if (atomic_get(&lab_enabled)) { send_phone(); }
		kr_runtime_owner_unlock();
		if (k_msgq_num_used_get(&radio_rx)) {
			k_sem_give(&wake);
		}
	}
	return 0;
}

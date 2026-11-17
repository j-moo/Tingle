#include "board.h"
#include "nus.h"
#include "product.h"
#include "storage.h"
#include "monocypher.h"
#include <errno.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>
#include <zephyr/random/random.h>

static struct bt_uuid_128 service_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400001));
static struct bt_uuid_128 rx_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400002));
static struct bt_uuid_128 tx_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400003));
static struct bt_uuid_128 profile_uuid = BT_UUID_INIT_128(KR_NUS_UUID(0x6e400004));
static struct kr_core core;
static struct kr_product application;
static atomic_t connection_epoch;
static struct k_sem *peer_wake;
static struct kr_peer_plan peer_plan;
static bool peer_pending;
static atomic_t has_settings;
static atomic_t reset_pending, reset_history, reset_recovery;
static int64_t window_deadline;
static struct bt_conn *peer;
static bt_addr_le_t failed_bond_address;
static uint32_t failed_bond_token;
static uint32_t generation;
static bool ready, busy;
static bool incoming_protected;
static struct kr_frame incoming;
static char frame[KR_FRAME_MAX + 1], response[KR_RESPONSE_MAX];
K_MUTEX_DEFINE(transport_lock);
K_MUTEX_DEFINE(core_lock);
static atomic_t quiescing;
K_MUTEX_DEFINE(peer_plan_lock);
K_SEM_DEFINE(request_ready, 0, 1);

void kr_nus_quiesce(void)
{
	atomic_set(&quiescing, 1);
	/* Let a synchronous settings/record write finish before shutting down. */
	k_mutex_lock(&core_lock, K_FOREVER);
	k_mutex_unlock(&core_lock);
}

static ssize_t receive_frame(struct bt_conn *conn, const struct bt_gatt_attr *attr,
	const void *buf, uint16_t len, uint16_t offset, uint8_t flags, bool protected)
{
	ARG_UNUSED(attr);
	if (protected && bt_conn_get_security(conn) < BT_SECURITY_L2) {
		return BT_GATT_ERR(BT_ATT_ERR_AUTHENTICATION);
	}
	if (offset || (flags & (BT_GATT_WRITE_FLAG_PREPARE | BT_GATT_WRITE_FLAG_CMD)) || !len || len > 20) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	k_mutex_lock(&transport_lock, K_FOREVER);
	if (conn != peer || busy || atomic_get(&quiescing)) {
		k_mutex_unlock(&transport_lock);
		return BT_GATT_ERR(BT_ATT_ERR_INSUFFICIENT_RESOURCES);
	}
	if (!incoming.size) { incoming_protected = protected; }
	if (incoming_protected != protected) {
		kr_frame_reset(&incoming);
		k_mutex_unlock(&transport_lock);
		bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
		return BT_GATT_ERR(BT_ATT_ERR_AUTHORIZATION);
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

static ssize_t receive(struct bt_conn *conn, const struct bt_gatt_attr *attr,
	const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	return receive_frame(conn, attr, buf, len, offset, flags, false);
}

static ssize_t receive_profile(struct bt_conn *conn, const struct bt_gatt_attr *attr,
	const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	return receive_frame(conn, attr, buf, len, offset, flags, true);
}

static ssize_t profile_security(struct bt_conn *conn, const struct bt_gatt_attr *attr,
	void *buf, uint16_t len, uint16_t offset)
{
	if (bt_conn_get_security(conn) < BT_SECURITY_L2) {
		return BT_GATT_ERR(BT_ATT_ERR_AUTHENTICATION);
	}
	/* A protected, nonpersonal read lets FE negotiate encryption BEFORE writing data. */
	const uint8_t encrypted = 1;
	return bt_gatt_attr_read(conn, attr, buf, len, offset, &encrypted, sizeof(encrypted));
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
	BT_GATT_CCC(subscribed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	/* Appended after TX/CCC so existing notification attribute indexes stay valid. */
	BT_GATT_CHARACTERISTIC(&profile_uuid.uuid, BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE,
		BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT,
		profile_security, receive_profile, NULL));

static void connected(struct bt_conn *conn, uint8_t error)
{
	if (error) { return; }
	k_mutex_lock(&transport_lock, K_FOREVER);
	peer = bt_conn_ref(conn);
	failed_bond_token = 0;
	++generation;
	atomic_inc(&connection_epoch);
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
		atomic_inc(&connection_epoch);
		kr_frame_reset(&incoming); ready = busy = false;
	}
	k_mutex_unlock(&transport_lock);
	printk("BLE disconnected reason=%u; incomplete frame discarded\n", reason);
}

static void security_changed(struct bt_conn *conn, bt_security_t level, enum bt_security_err error)
{
	if (!error && level >= BT_SECURITY_L2) {
		k_mutex_lock(&transport_lock, K_FOREVER);
		if (peer == conn) { failed_bond_token = 0; }
		k_mutex_unlock(&transport_lock);
	}
	printk("BLE security connection=%u level=%u error=%u\n",
		(unsigned)atomic_get(&connection_epoch), level, error);
}

BT_CONN_CB_DEFINE(callbacks) = {
	.connected = connected, .disconnected = disconnected, .security_changed = security_changed,
};

struct bond_match {
	const bt_addr_le_t *address;
	bool found;
};

static void match_bond(const struct bt_bond_info *bond, void *context)
{
	struct bond_match *match = context;
	if (bt_addr_le_eq(&bond->addr, match->address)) { match->found = true; }
}

static void pairing_failed(struct bt_conn *conn, enum bt_security_err error)
{
	struct bond_match match = {0};
	struct bt_conn_info info;
	uint32_t token = 0;
	k_mutex_lock(&transport_lock, K_FOREVER);
	unsigned connection = (unsigned)atomic_get(&connection_epoch);
	if (conn == peer) {
		failed_bond_token = 0;
		if (!bt_conn_get_info(conn, &info) && info.type == BT_CONN_TYPE_LE &&
			info.id == BT_ID_DEFAULT && info.le.dst && !bt_addr_le_eq(info.le.dst, BT_ADDR_LE_ANY)) {
			match.address = info.le.dst;
			bt_foreach_bond(info.id, match_bond, &match);
			if (match.found && error == BT_SECURITY_ERR_AUTH_REQUIREMENT) {
				token = sys_rand32_get();
				if (!token) { token = 1; }
				bt_addr_le_copy(&failed_bond_address, match.address);
				failed_bond_token = token;
			}
		}
	}
	k_mutex_unlock(&transport_lock);
	printk("BLE pairing failed connection=%u error=%u knownBond=%u\n", connection, error, match.found);
	if (token) { printk("BLE bond repair available: BLE FORGET %08x\n", token); }
}

static void pairing_complete(struct bt_conn *conn, bool bonded)
{
	ARG_UNUSED(conn);
	printk("BLE pairing complete connection=%u bonded=%u\n",
		(unsigned)atomic_get(&connection_epoch), bonded);
}

static struct bt_conn_auth_info_cb pairing_callbacks = {
	.pairing_complete = pairing_complete, .pairing_failed = pairing_failed,
};

/* Called from the USB console with phone advertising stopped. A new
 * connection or successful encryption invalidates an earlier repair token. */
int kr_nus_forget_failed_bond(uint32_t token)
{
	k_mutex_lock(&transport_lock, K_FOREVER);
	if (peer || atomic_get(&reset_pending) || atomic_get(&quiescing)) {
		k_mutex_unlock(&transport_lock);
		return -EBUSY;
	}
	if (!token || token != failed_bond_token) {
		k_mutex_unlock(&transport_lock);
		return -ENOENT;
	}
	bt_addr_le_t address = failed_bond_address;
	k_mutex_unlock(&transport_lock);

	int result = kr_board_forget_phone_bond(&address);
	if (!result) {
		k_mutex_lock(&transport_lock, K_FOREVER);
		if (failed_bond_token == token) { failed_bond_token = 0; }
		k_mutex_unlock(&transport_lock);
	}
	return result;
}

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
	atomic_set(&has_settings, core.current.magic != 0 || core.settings_recovery_required || core.storage_error);
	atomic_set(&reset_pending, core.reset.phase == KR_RESETTING);
	atomic_set(&reset_history, core.reset.ownership_epoch != 0);
	atomic_set(&reset_recovery, core.reset.ownership_epoch && !core.current.magic);
	if (!result) { result = bt_conn_auth_info_cb_register(&pairing_callbacks); }
	return result;
}

bool kr_nus_reset_pending(void) { return atomic_get(&reset_pending) != 0; }
bool kr_nus_storage_fault(void)
{
	k_mutex_lock(&core_lock, K_FOREVER);
	bool fault = core.storage_error || core.settings_recovery_required;
	k_mutex_unlock(&core_lock);
	return fault;
}
bool kr_nus_lab_allowed(void) { return !core.settings_recovery_required && !atomic_get(&reset_history) && !atomic_get(&reset_pending); }

/* Called by main only after stopping peer radio and disabling the synthetic engine. */
const char *kr_nus_finish_reset(void)
{
	k_mutex_lock(&core_lock, K_FOREVER);
	const char *error = kr_reset_resume(&core);
	atomic_set(&reset_pending, core.reset.phase == KR_RESETTING);
	atomic_set(&has_settings, core.current.magic != 0 || core.settings_recovery_required || core.storage_error);
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
	peer_plan.generation = kr_runtime_generation();
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
			printk("DIAG %s NUS ready; settingsPolicy=%s; storedVersion=%lld; settingsRecoveryRequired=%u\n",
				KR_FIRMWARE_VERSION, core.platform.issue_settings_nonce ? "DEV_SIGNED_V1" : "UNRESOLVED",
				(long long)core.current.version, core.settings_recovery_required);
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
		kr_runtime_owner_lock();
		bool record_error = application.engine && application.engine->storage_error;
		unsigned quarantine = application.engine ? kr_quarantine_count(application.engine) : 0;
		printk("HEALTH uptime=%lld batteryMv=%d charging=%d storageError=%u registrationStorageError=%u recordStorageError=%u quarantinedRecords=%u quarantineUnresolved=%08x\n",
			(long long)(now / 1000), status.battery_mv, status.charging,
			core.storage_error || record_error || quarantine, core.storage_error, record_error, quarantine, application.quarantine_unresolved);
		kr_runtime_owner_unlock();
		printk("POWER protection=%s charge=%s configuredMa=%d usbSeconds=%u\n",
			status.battery_power_state, status.charge_monitor_state,
			status.charge_current_ma, status.charge_elapsed_seconds);
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
		bool protected = false;
		k_mutex_lock(&transport_lock, K_FOREVER);
		uint32_t request_generation = generation;
		uint32_t request_epoch = (uint32_t)atomic_get(&connection_epoch);
		if (ready && peer) {
			memcpy(frame, incoming.bytes, incoming.size);
			conn = bt_conn_ref(peer);
			protected = incoming_protected && bt_conn_get_security(peer) >= BT_SECURITY_L2;
			ready = false;
		}
		k_mutex_unlock(&transport_lock);
		if (!conn) {
			continue;
		}

		k_mutex_lock(&core_lock, K_FOREVER);
		if (atomic_get(&quiescing)) {
			k_mutex_unlock(&core_lock);
			bt_conn_unref(conn);
			crypto_wipe(frame, sizeof(frame));
			continue;
		}
		core.profile_transport_protected = protected;
		core.connection_epoch = request_epoch;
		kr_request(&core, frame, kr_board_status(), !core.current.magic &&
			k_uptime_get() < window_deadline, response);
		const char *error_code = strstr(response, "\"code\":\"");
		if (error_code) {
			error_code += strlen("\"code\":\"");
			printk("NUS error=%.*s storedVersion=%lld recovery=%u\n",
				(int)strcspn(error_code, "\""), error_code, (long long)core.current.version, core.settings_recovery_required);
		}
		core.profile_transport_protected = false;
		atomic_set(&has_settings, core.current.magic != 0 || core.settings_recovery_required || core.storage_error);
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
		bool activate_peer = !atomic_get(&quiescing) && kr_peer_after_response(&core, result == 0, &plan);
		kr_request_forget_candidate();
		k_mutex_unlock(&core_lock);
		if (activate_peer) {
			publish_peer_plan(&plan);
		}
		printk("NUS response bytes=%u result=%d storedVersion=%lld\n",
			(unsigned)strlen(response), result, (long long)stored_version);
		/* A partial response cannot be followed by a new JSON frame. */
		if (result) { bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN); }
		bt_conn_unref(conn);
		crypto_wipe(frame, sizeof(frame)); crypto_wipe(response, sizeof(response));
		k_mutex_lock(&transport_lock, K_FOREVER);
		if (generation == request_generation) { busy = false; kr_frame_reset(&incoming); }
		k_mutex_unlock(&transport_lock);
	}
}

/* Notification backpressure must not stall the lab's scan and matching loop. */
K_THREAD_DEFINE(nus_worker, 8192, process_requests, NULL, NULL, NULL, 7, 0, 0);

static int64_t application_uptime(void *ctx) { ARG_UNUSED(ctx); return k_uptime_get(); }
static int application_random(void *ctx, uint8_t *bytes, size_t length) { ARG_UNUSED(ctx); return sys_csrand_get(bytes, length); }
static void application_stop(void *ctx) { ARG_UNUSED(ctx); kr_runtime_stop_feedback(); }
static void application_refresh_phone(void *ctx) { ARG_UNUSED(ctx); kr_runtime_refresh_phone(); }
static uint32_t application_generation(void *ctx) { ARG_UNUSED(ctx); return kr_runtime_generation(); }
static int application_record(void *ctx, unsigned slot, const struct kr_record *record)
{
	ARG_UNUSED(ctx); return kr_product_record(&application, slot, record);
}
static int application_suppression(void *ctx, unsigned slot, const struct kr_suppression *s)
{ ARG_UNUSED(ctx); return kr_product_suppression(&application, slot, s); }
static bool application_same_peer(void *ctx, unsigned slot, uint64_t peer)
{ ARG_UNUSED(ctx); return kr_product_same_peer(&application, slot, peer); }
static bool application_candidate(void *ctx, unsigned slot)
{ ARG_UNUSED(ctx); return kr_product_candidate_ready(&application, slot); }
static uint64_t application_error_key(void *ctx, uint64_t peer, const uint8_t *raw, size_t len)
{ ARG_UNUSED(ctx); return kr_product_error_key(&application, peer, raw, len); }
static bool application_request(void *ctx, const struct kr_json *json, const char *request,
	struct kr_status status, char *response)
{
	ARG_UNUSED(ctx); kr_runtime_owner_lock();
	bool handled = kr_product_dispatch(&application, json, request, status, response);
	kr_runtime_owner_unlock(); return handled;
}
void kr_nus_attach_application(struct kr_engine *engine)
{
	uint8_t key[32] = {0};
	(void)hex2bin(KR_DEV_SERVER_KEY_HEX, 64, key, sizeof(key)); /* CMake가 64자리 hex를 보장한다. */
	struct kr_product_io io = {.uptime_ms = application_uptime, .random = application_random,
		.blob = kr_storage_blob, .space = kr_storage_space, .stop_feedback = application_stop,
		.refresh_phone = application_refresh_phone, .generation = application_generation};
	kr_product_init(&application, &core, engine, io, key); core.platform.application = application_request;
	engine->io.record = application_record;
	engine->io.suppression = application_suppression;
	engine->io.same_peer = application_same_peer; engine->io.candidate_ready = application_candidate;
	engine->io.error_key = application_error_key;
}

size_t kr_nus_copy_public_name(char name[KR_PUBLIC_NAME_MAX + 1])
{
	k_mutex_lock(&core_lock, K_FOREVER);
	kr_runtime_owner_lock();
	const char *saved = kr_product_public_name(&application);
	size_t length = strlen(saved);
	memcpy(name, saved, length + 1);
	kr_runtime_owner_unlock();
	k_mutex_unlock(&core_lock);
	return length;
}
int kr_nus_apply_application(const struct kr_peer_plan *plan, struct kr_peer_runtime *peers)
{
	k_mutex_lock(&core_lock, K_FOREVER); kr_runtime_owner_lock();
	int result = -EAGAIN;
	if (plan->generation == kr_runtime_generation() &&
		(!plan->settings_apply_attempt || kr_settings_apply_current(&core, plan))) {
		bool applied = kr_product_apply(&application, peers, plan->verified_time);
		result = plan->settings_apply_attempt && !applied ? -EINVAL : 0;
	}
	kr_runtime_owner_unlock(); k_mutex_unlock(&core_lock); return result;
}
void kr_nus_application_result(const struct kr_peer_plan *plan, int error)
{
	if (!plan->settings_apply_attempt) { return; }
	k_mutex_lock(&core_lock, K_FOREVER); kr_runtime_owner_lock();
	if (plan->generation == kr_runtime_generation()) {
		kr_settings_apply_finished(&core, plan, !error);
	}
	kr_runtime_owner_unlock(); k_mutex_unlock(&core_lock);
}
void kr_nus_bind_application(struct kr_peer_runtime *peers) { kr_product_bind_peer(&application, peers); }
void kr_nus_clear_application(void) { kr_product_forget_owner(&application); }
int kr_nus_write_record(unsigned slot, const struct kr_record *record) { return kr_product_record(&application, slot, record); }
void kr_nus_application_tick(void) { kr_product_tick(&application); }
uint64_t kr_nus_application_time(void) { return kr_product_now(&application); }
unsigned kr_nus_feedback_presence_seconds(void)
{
	return kr_product_ready(&application) ? application.policy.presence_seconds : 0;
}

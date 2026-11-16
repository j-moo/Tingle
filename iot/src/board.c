#include "board.h"
#include "power.h"
#include "settings_storage.h"
#include "flash_guard.h"
#include "nus.h"
#include "profile.h"
#include "policy.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <mbedtls/sha256.h>
#include <zephyr/device.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/kernel.h>
#include <zephyr/random/random.h>
#include <zephyr/settings/settings.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/sys/util.h>
#include <zephyr/sys/crc.h>
#include <hal/nrf_saadc.h>
#include <hal/nrf_power.h>
#if DT_NODE_EXISTS(DT_ALIAS(keyring_fuel_gauge))
#include <zephyr/drivers/fuel_gauge.h>
static const struct device *gauge = DEVICE_DT_GET(DT_ALIAS(keyring_fuel_gauge));
#endif

static struct nvs_fs fs;
static struct kr_settings readback;
static struct kr_reset_record reset_readback;
static const struct device *gpio = DEVICE_DT_GET(DT_NODELABEL(gpio0));
static const struct device *adc = DEVICE_DT_GET(DT_NODELABEL(adc));
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);
static const struct gpio_dt_spec rgb[] = {
	GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios),
	GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios),
	GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios),
};
static bool led_ready, led_error, led_diagnostic, led_usb, led_stopped;
static enum kr_charge_level led_charge;
K_MUTEX_DEFINE(led_lock);
static bool battery_ready;
static bool charge_configured;
static struct kr_power_monitor power_monitor;
K_MUTEX_DEFINE(power_lock);

int kr_board_charge_init(void)
{
	/* DTP102050 1000 mAh: 100 mA is below its 200 mA standard/500 mA maximum.
	 * This does not disable the charger's 10-hour safety timer or sense temperature. */
	int result = device_is_ready(gpio) ? gpio_pin_configure(gpio, 13, GPIO_OUTPUT_LOW) : -ENODEV;
	charge_configured = !result;
	return result;
}

int kr_board_power_init(void)
{
	if (!device_is_ready(gpio) || !device_is_ready(adc)) { return -ENODEV; }
	/* P0.14 must stay LOW for the divider; HIGH can overdrive AIN7. */
	struct adc_channel_cfg channel = {.gain = ADC_GAIN_1_6, .reference = ADC_REF_INTERNAL,
		.acquisition_time = ADC_ACQ_TIME(ADC_ACQ_TIME_MICROSECONDS, 40),
		.channel_id = 0, .input_positive = NRF_SAADC_INPUT_AIN7};
	int result = gpio_pin_configure(gpio, 14, GPIO_OUTPUT_LOW);
	if (!result) { result = gpio_pin_configure(gpio, 17, GPIO_INPUT); }
	if (!result) { result = adc_channel_setup(adc, &channel); }
	battery_ready = !result;
	return result;
}

void kr_board_cancel_charge_notice(void)
{
	k_mutex_lock(&power_lock, K_FOREVER);
	kr_power_cancel_notice(&power_monitor);
	k_mutex_unlock(&power_lock);
}

void kr_board_start_charge_notice(void)
{
	k_mutex_lock(&power_lock, K_FOREVER);
	kr_power_start_notice(&power_monitor, k_uptime_get());
	k_mutex_unlock(&power_lock);
}

#ifdef KR_DEV_SERVER_KEY_HEX
static uint8_t server_key[32];
static struct kr_settings_nonce challenge;

static const char *issue_settings_nonce(void *ctx, char out[44])
{
	(void)ctx;
	uint8_t bytes[32];
	if (sys_csrand_get(bytes, sizeof(bytes))) {
		return "RANDOM_ERROR";
	}
	kr_issue_settings_nonce(&challenge, bytes, k_uptime_get());
	memcpy(out, challenge.value, sizeof(challenge.value));
	return NULL;
}

static const char *issue_reset_challenge(void *ctx, char signature[87])
{
	uint8_t bytes[48];
	if (sys_csrand_get(bytes, sizeof(bytes))) { return "RANDOM_ERROR"; }
	return kr_reset_issue(ctx, bytes, k_uptime_get(), signature);
}

static const char *accept_reset(void *ctx, const struct kr_json *json)
{
	return kr_reset_begin(ctx, json, k_uptime_get(), KR_DEV_SERVER_KEY_ID, server_key);
}
#endif
BUILD_ASSERT(sizeof(struct kr_settings) < 3900, "Atomic NVS record must fit one erase sector");

static int put(uint16_t key, const void *data, size_t length)
{
	ssize_t result = nvs_write(&fs, key, data, length);
	return result == (ssize_t)length || result == 0 ? 0 : result < 0 ? (int)result : -EIO;
}

static int read_settings(void *ctx, uint16_t key, unsigned history, struct kr_settings *out)
{
	(void)ctx;
	memset(out, 0, sizeof(*out));
	int got = (int)nvs_read_hist(&fs, key, out, sizeof(*out), history);
	if (got != -ENOENT && (got != sizeof(*out) || !kr_settings_valid(out))) {
		unsigned zero = 0, erased = 0, other = 0;
		if (out->payload_size <= KR_PAYLOAD_MAX) {
			for (unsigned i = out->payload_size; i < KR_PAYLOAD_MAX; ++i) {
				if (out->payload[i] == 0) { ++zero; }
				else if (out->payload[i] == 0xff) { ++erased; }
				else { ++other; }
			}
		}
		printk("SETTINGS READ key=%u history=%u result=%d schema=%u dataCrc=%08x unusedZero=%u unusedFF=%u unusedOther=%u\n",
			key, history, got, kr_settings_valid(out), crc32_ieee((const uint8_t *)out, sizeof(*out)), zero, erased, other);
	}
	return got;
}

static int write_settings(void *ctx, uint16_t key, const struct kr_settings *value)
{
	(void)ctx;
	struct kr_status power = kr_board_status();
	uint32_t before = crc32_ieee((const uint8_t *)value, sizeof(*value));
	printk("SETTINGS WRITE begin key=%u version=%lld bytes=%u crc=%08x mv=%d usb=%u\n",
		key, (long long)value->version, (unsigned)sizeof(*value), before, power.battery_mv, power.usb_present);
	ssize_t result = nvs_write(&fs, key, value, sizeof(*value));
	uint32_t after = crc32_ieee((const uint8_t *)value, sizeof(*value));
	printk("SETTINGS WRITE end key=%u result=%d ramStable=%u\n", key, (int)result, before == after);
	if (before != after) { return -EIO; }
	return result == sizeof(*value) || result == 0 ? 0 : result < 0 ? (int)result : -EIO;
}

static bool recover_settings_owner(void *ctx, const struct kr_core *core, const struct kr_settings *value)
{
	(void)ctx;
#ifdef KR_DEV_SERVER_KEY_HEX
	return kr_settings_recovery_owner(core, value, server_key);
#else
	(void)core; (void)value;
	return false;
#endif
}

static const struct kr_settings_storage settings_io = {
	.read = read_settings, .write = write_settings, .recover_owner = recover_settings_owner,
};

static int save(void *ctx, const struct kr_settings *next)
{
	struct kr_core *core = ctx;
	/* Serialize the settings commit with encounter processing. Once a verified
	 * change commits (including OFF), no old snapshot may create new work. */
	kr_runtime_owner_lock();
	kr_runtime_pause_matching();
	int result = kr_settings_storage_save(&settings_io,
		core->settings_owner_only ? NULL : &core->current, next, &readback);
	printk("SETTINGS COMMIT version=%lld result=%d verified=%u\n", (long long)next->version, result, result == 0);
	kr_runtime_owner_unlock();
	return result;
}

static int save_reset(void *ctx, const struct kr_reset_record *next)
{
	(void)ctx;
	int result = put(20, next, sizeof(*next));
	if (result) { return result; }
	ssize_t got = nvs_read(&fs, 20, &reset_readback, sizeof(reset_readback));
	return got == sizeof(reset_readback) && !memcmp(next, &reset_readback, sizeof(*next)) ? 0 : -EIO;
}

static const char *begin_reset(void *ctx, const struct kr_reset_record *next)
{
	kr_runtime_owner_lock();
	int count = kr_runtime_pending_records();
	const char *error = NULL;
	if (count < 0) { error = "RECORD_STATE_UNKNOWN"; }
	else if (next->policy == KR_RESET_REQUIRE_SYNCED && count) { error = "PENDING_RECORDS"; }
	else {
		if (save_reset(ctx, next)) { error = "STORAGE_ERROR"; }
		/* An uncertain write may already contain the durable reset intent. */
		kr_runtime_reset_block();
	}
	kr_runtime_owner_unlock();
	return error;
}

static void disconnect_for_reset(struct bt_conn *conn, void *ctx)
{
	struct bt_conn_info info;
	bool *active = ctx;
	/* A connectable advertiser also reserves a CONNECTING object. It has no
	 * peer credentials yet and must remain available for recovery queries. */
	if (bt_conn_get_info(conn, &info) || info.state == BT_CONN_STATE_CONNECTED ||
		info.state == BT_CONN_STATE_DISCONNECTING) {
		*active = true;
		(void)bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
	}
}

static int find_bond_setting(const char *key, size_t len, settings_read_cb read,
	void *read_ctx, void *ctx)
{
	ARG_UNUSED(read); ARG_UNUSED(read_ctx);
	char *name = ctx;
	if (len && key && (!strncmp(key, "keys/", 5) || !strncmp(key, "ccc/", 4) ||
		!strncmp(key, "sc/", 3) || !strncmp(key, "cf/", 3))) {
		int n = snprintf(name, 128, "bt/%s", key);
		return n < 0 || n >= 128 ? -EOVERFLOW : 1;
	}
	return 0;
}

static const char *clear_bonds(void)
{
	/* bt_unpair() discards backend deletion errors in Zephyr 4.2. Verify the
	 * durable settings separately, including orphaned entries from a power cut.
	 * Wait for active SMP/connection callbacks before issuing CLEARED. NUS does
	 * not need bonding; the synthetic encrypted service stays disabled. */
	bt_set_bondable(false);
	bool active = false;
	bt_conn_foreach(BT_CONN_TYPE_LE, disconnect_for_reset, &active);
	if (active) { return "BOND_CLEAR_PENDING"; }
	for (uint8_t id = 0; id < CONFIG_BT_ID_MAX; ++id) {
		if (bt_unpair(id, BT_ADDR_LE_ANY)) { return "BOND_CLEAR_FAILED"; }
	}
	for (;;) {
		char name[128] = "";
		int result = settings_load_subtree_direct("bt", find_bond_setting, name);
		if (result < 0 || (result && !name[0])) { return "BOND_CLEAR_FAILED"; }
		if (!name[0]) { return NULL; }
		if (settings_delete(name)) { return "BOND_CLEAR_FAILED"; }
		ssize_t remaining = settings_get_val_len(name);
		if (remaining != -ENOENT && remaining != 0) { return "BOND_CLEAR_FAILED"; }
	}
}

int kr_board_forget_phone_bond(const bt_addr_le_t *address)
{
	if (!address || address->type > BT_ADDR_LE_RANDOM || bt_addr_le_eq(address, BT_ADDR_LE_ANY)) {
		return -EINVAL;
	}
	int result = bt_unpair(BT_ID_DEFAULT, address);
	if (result) { return result; }

	/* Zephyr 4.2 uses bt/<subsystem>/<address><type> for the default identity.
	 * Verify that exact key after bt_unpair(), which drops settings errors. */
	static const char *const subsystems[] = {"keys", "ccc", "sc", "cf"};
	const uint8_t *a = address->a.val;
	for (size_t i = 0; i < ARRAY_SIZE(subsystems); ++i) {
		char name[32];
		snprintk(name, sizeof(name), "bt/%s/%02x%02x%02x%02x%02x%02x%u",
			subsystems[i], a[5], a[4], a[3], a[2], a[1], a[0], address->type);
		ssize_t remaining = settings_get_val_len(name);
		if (remaining == -ENOENT || remaining == 0) { continue; }
		if (remaining < 0) { return (int)remaining; }
		result = settings_delete(name);
		if (result) { return result; }
		remaining = settings_get_val_len(name);
		if (remaining != -ENOENT && remaining != 0) { return -EIO; }
	}
	return 0;
}

static const char *erase_owner(void *ctx)
{
	(void)ctx;
	kr_runtime_owner_lock();
	int result = kr_runtime_clear_owner_data();
	kr_runtime_owner_unlock();
	if (result) { return "STORAGE_ERROR"; }
	/* Clear both committed slots while RESETTING is durable. Only then may a
	 * tombstone represent an unowned board or RESET_DONE be acknowledged. */
	result = nvs_delete(&fs, KR_SETTINGS_REPAIR_KEY);
	if (result < 0 && result != -ENOENT) { return "STORAGE_ERROR"; }
	uint8_t repair_byte;
	if (nvs_read(&fs, KR_SETTINGS_REPAIR_KEY, &repair_byte, sizeof(repair_byte)) != -ENOENT) { return "STORAGE_ERROR"; }
	result = nvs_delete(&fs, 10);
	if (result < 0 && result != -ENOENT) { return "STORAGE_ERROR"; }
	uint8_t byte;
	if (nvs_read(&fs, 10, &byte, sizeof(byte)) != -ENOENT) { return "STORAGE_ERROR"; }
	result = nvs_delete(&fs, KR_SETTINGS_BACKUP_KEY);
	if (result < 0 && result != -ENOENT) { return "STORAGE_ERROR"; }
	if (nvs_read(&fs, KR_SETTINGS_BACKUP_KEY, &byte, sizeof(byte)) != -ENOENT) { return "STORAGE_ERROR"; }
	memset(&readback, 0, sizeof(readback));
#ifdef KR_DEV_SERVER_KEY_HEX
	memset(&challenge, 0, sizeof(challenge));
#endif
	return clear_bonds();
}

static int load_reset(struct kr_core *core)
{
	ssize_t got = nvs_read(&fs, 20, &core->reset, sizeof(core->reset));
	uint8_t marker;
	ssize_t marked = nvs_read(&fs, 21, &marker, sizeof(marker));
	if (got == -ENOENT) {
		if (marked != -ENOENT) { return -EILSEQ; }
		memset(&core->reset, 0, sizeof(core->reset));
		core->reset.magic = KR_RESET_MAGIC;
		if (save_reset(core, &core->reset)) { return -EIO; }
	} else if (got != sizeof(core->reset) || !kr_reset_record_valid(core)) { return -EILSEQ; }
	if (marked != -ENOENT && (marked != 1 || marker != 1)) { return -EILSEQ; }
	marker = 1;
	return put(21, &marker, sizeof(marker));
}

static const char *authorize(void *ctx, const struct kr_settings *next,
	const struct kr_settings_auth *auth, struct kr_peer_plan *plan)
{
#ifdef KR_DEV_SERVER_KEY_HEX
	const struct kr_core *core = ctx;
	const char *error = kr_verify_dev_settings(&core->identity, next, auth,
		&challenge, k_uptime_get(), KR_DEV_SERVER_KEY_ID, server_key, &plan->verified_time);
	if (error) {
		return error;
	}
	bool matching = next->payload[0] != 0;
	if (!strcmp(next->format, KR_PROFILE_STORAGE_FORMAT) || kr_policy_format(next->format)) {
		struct kr_profile_data profile;
		struct kr_policy policy;
		bool operational = kr_policy_format(next->format);
		if (operational && !kr_policy_decode(next->payload, next->payload_size, &policy)) { return "INVALID_SETTINGS"; }
		if (!kr_profile_data_decode(operational ? policy.profile : next->payload,
			operational ? 196 : next->payload_size, &profile)) {
			return "INVALID_SETTINGS";
		}
		matching = (profile.options & 1u) != 0;
		if (operational && (!policy.complete || !policy.enabled)) { matching = false; }
		if (matching) {
			uint8_t seed[32];
			if (sys_csrand_get(seed, sizeof(seed))) { return "RANDOM_ERROR"; }
			bool prepared = kr_peer_material_make(&plan->peer, &core->identity, next, auth, seed);
			crypto_wipe(seed, sizeof(seed));
			if (!prepared) { return "PEER_SETTINGS_UNSUPPORTED"; }
		}
	}
	if (matching) {
		static const uint8_t uuid[] = {KR_DEV_DISCOVERY_UUID};
		memcpy(plan->service_data, uuid, sizeof(uuid));
		if (sys_csrand_get(plan->service_data + sizeof(uuid), KR_DEV_DISCOVERY_BYTES - sizeof(uuid))) {
			return "RANDOM_ERROR";
		}
		plan->length = KR_DEV_DISCOVERY_BYTES;
	}
	return NULL;
#else
	(void)ctx;
	(void)next;
	(void)auth;
	(void)plan;
	/* S15P31E106-225 dependency: Notion sync-runs explicitly returns
	 * POLICY_UNRESOLVED until the official payload provider exists.
	 * No insecure runtime switch: replace this adapter only after agreement on
	 * authenticated owner/server envelope, schema and verification vectors.
	 * https://app.notion.com/p/3ffc91ccb66080d58b62dfbbf647b3b8 */
	return "POLICY_UNRESOLVED";
#endif
}

static int identity(struct kr_identity *id)
{
	uint8_t hardware[8];
	char expected_serial[65] = "XIAO-";
	if (hwinfo_get_device_id(hardware, sizeof(hardware)) != sizeof(hardware)) { return -EIO; }
	kr_hex(hardware, sizeof(hardware), expected_serial + 5);
	ssize_t got = nvs_read(&fs, 1, id, sizeof(*id));
	if (got == -ENOENT) {
		uint8_t marker;
		if (nvs_read(&fs, 2, &marker, sizeof(marker)) != -ENOENT) { return -EILSEQ; }
		memset(id, 0, sizeof(*id));
		uint8_t seed[32];
		if (sys_csrand_get(seed, sizeof(seed))) { return -EIO; }
		crypto_ed25519_key_pair(id->secret, id->public_key, seed);
		strcpy(id->serial, expected_serial);
		strcpy(id->key_id, "ed25519-");
		kr_hex(id->public_key, 8, id->key_id + 8);
		int result = put(1, id, sizeof(*id));
		if (result) { return result; }
		struct kr_identity verify;
		got = nvs_read(&fs, 1, &verify, sizeof(verify));
		bool same = got == sizeof(verify) && !memcmp(id, &verify, sizeof(verify));
		crypto_wipe(&verify, sizeof(verify));
		if (!same) { return -EIO; }
	} else if (got != sizeof(*id)) { return -EILSEQ; }
	if (id->serial[64] || id->key_id[64] || strcmp(id->serial, expected_serial) ||
		!kr_identifier(id->key_id)) { return -EILSEQ; }
	uint8_t seed[32], secret[64], public_key[32];
	memcpy(seed, id->secret, 32);
	crypto_ed25519_key_pair(secret, public_key, seed);
	bool valid = !crypto_verify64(secret, id->secret) && !crypto_verify32(public_key, id->public_key);
	crypto_wipe(secret, sizeof(secret));
	if (!valid) { return -EILSEQ; }
	uint8_t marker = 1;
	return put(2, &marker, sizeof(marker));
}

int kr_board_open(struct kr_core *core)
{
	core->platform = (struct kr_platform){.ctx = core, .save = save, .authorize = authorize,
		.begin_reset = begin_reset, .save_reset = save_reset, .erase_owner = erase_owner};
#ifdef KR_DEV_SERVER_KEY_HEX
	const char *public_hex = KR_DEV_SERVER_KEY_HEX;
	if (hex2bin(public_hex, strlen(public_hex), server_key, sizeof(server_key)) != sizeof(server_key)) {
		return -EINVAL;
	}
	core->platform.issue_settings_nonce = issue_settings_nonce;
	core->platform.issue_reset_challenge = issue_reset_challenge;
	core->platform.accept_reset = accept_reset;
#endif
	if (gpio_is_ready_dt(&led)) { gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE); }
	const struct device *flash = FIXED_PARTITION_DEVICE(registration_store);
	int result = device_is_ready(flash) ? 0 : device_init(flash);
	if (result) { return result; }
	const struct flash_area *area;
	result = flash_area_open(FIXED_PARTITION_ID(registration_store), &area);
	if (result) { return result; }
	fs.flash_device = kr_flash_guard_device();
	fs.offset = area->fa_off;
	fs.sector_size = 4096;
	fs.sector_count = area->fa_size / fs.sector_size;
	result = nvs_mount(&fs);
	flash_area_close(area);
	if (result) { return result; } /* Never auto-format, erase or rotate keys. */
	result = identity(&core->identity);
	if (result) { return result; }
	result = load_reset(core);
	if (result) { return result; }
	if (core->reset.phase == KR_RESETTING) {
		memset(&core->current, 0, sizeof(core->current));
		kr_runtime_reset_block();
		kr_board_print_identity(&core->identity);
		return 0;
	}
	result = kr_settings_storage_load(core, &settings_io);
	if (result < 0) {
		/* Identity and reset epoch were verified above. Keep diagnostic BLE, but
		 * never pretend an unknown owner is unowned or allow registration/sync. */
		core->storage_error = true;
		printk("STORAGE owner-unresolved: settings preserved; diagnostic BLE only, no registration/sync\n");
	}
	if (result == 1) {
		printk("STORAGE recovery-required: retained verified settings v%lld; fresh signed sync required; no rewrite\n",
			(long long)core->current.version);
	}
	if (result == 2) {
		printk("STORAGE signed-owner-only: version=%lld epoch=%lld; no settings rewrite/apply; fresh signed sync required\n",
			(long long)core->current.version, (long long)core->reset.ownership_epoch);
	}
	kr_board_print_identity(&core->identity);
	return 0;
}

void kr_board_print_identity(const struct kr_identity *id)
{
	/* Public enrollment material only. The BE trust table must be provisioned
	 * out of band; discovering a public key over BLE does not establish trust.
	 * External flash has no physical extraction protection on this prototype. */
	uint8_t spki[44] = {0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00};
	char encoded[60];
	memcpy(spki + 12, id->public_key, 32);
	kr_base64_encode(spki, sizeof(spki), encoded);
	printk("ENROLL serial=%s keyId=%s publicKeySpkiBase64url=%s\n",
		id->serial, id->key_id, encoded);
}

int kr_board_storage_check(void)
{
	/* Called with the runtime owner lock, so settings/record writes cannot race.
	 * USB exposes only whole-partition hashes, never keys or payload bytes. */
	const struct device *flash = kr_flash_guard_device();
	const uint32_t starts[] = {FIXED_PARTITION_OFFSET(registration_store), FIXED_PARTITION_OFFSET(keyring_store)};
	const uint32_t sizes[] = {FIXED_PARTITION_SIZE(registration_store), FIXED_PARTITION_SIZE(keyring_store)};
	uint32_t buffer[64]; uint8_t digest[32]; char hex[65];
	for (unsigned area = 0; area < ARRAY_SIZE(starts); ++area) {
		mbedtls_sha256_context hash;
		mbedtls_sha256_init(&hash);
		int result = mbedtls_sha256_starts(&hash, 0);
		for (uint32_t offset = 0; !result && offset < sizes[area]; offset += sizeof(buffer)) {
			result = flash_read(flash, starts[area] + offset, buffer, sizeof(buffer));
			if (!result) { result = mbedtls_sha256_update(&hash, (const uint8_t *)buffer, sizeof(buffer)); }
		}
		if (!result) { result = mbedtls_sha256_finish(&hash, digest); }
		mbedtls_sha256_free(&hash); crypto_wipe(buffer, sizeof(buffer));
		if (result) { return result; }
		kr_hex(digest, sizeof(digest), hex);
		printk("STORAGE CHECK area=%x bytes=%u sha256=%s\n", starts[area], sizes[area], hex);
	}
	return 0;
}

bool kr_board_usb_present(void)
{
	return nrf_power_usbregstatus_vbusdet_get(NRF_POWER);
}

static struct kr_status read_power(void)
{
	struct kr_status status = {.battery_mv = -1, .charging = -1};
	status.usb_valid = true;
	status.usb_present = kr_board_usb_present();
	if (!status.usb_present) { status.charging = 0; }
#if DT_NODE_EXISTS(DT_ALIAS(keyring_fuel_gauge))
	status.gauge_ready = true;
	if (device_is_ready(gauge)) {
		union fuel_gauge_prop_val value;
		if (!fuel_gauge_get_prop(gauge, FUEL_GAUGE_PRESENT_STATE, &value)) {
			status.presence_valid = true; status.battery_present = value.present_state;
		}
		if (!fuel_gauge_get_prop(gauge, FUEL_GAUGE_RELATIVE_STATE_OF_CHARGE, &value) && value.relative_state_of_charge <= 100) {
			status.gauge_percent_valid = true; status.gauge_percent = value.relative_state_of_charge;
		}
	}
#endif
	if (!battery_ready) { return status; }
	if (status.usb_present) {
		int charge = gpio_pin_get(gpio, 17);
		if (charge >= 0) { status.charging = !charge; }
	}
	int16_t raw[8];
	struct adc_sequence sequence = {.channels = BIT(0), .buffer = &raw,
		.buffer_size = sizeof(raw), .resolution = 12};
	/* Complete each sample before starting the next. A 100 us kernel timer
	 * overruns SAADC on this board (-EBUSY), discarding otherwise valid voltage. */
	const struct adc_sequence_options options = {.extra_samplings = 7, .interval_us = 0};
	sequence.options = &options;
	if (!adc_read(adc, &sequence)) {
		int32_t mv = 0; for (unsigned i = 0; i < 8; ++i) { if (raw[i] <= 0) { return status; } mv += raw[i]; }
		mv = (mv + 4) / 8;
		if (!adc_raw_to_millivolts(600, ADC_GAIN_1_6, 12, &mv)) {
			/* R16=1M, R17=510k. Voltage does not prove battery presence. */
			status.battery_mv = (mv * 1510 + 255) / 510;
		}
	}
	return status;
}

struct kr_status kr_board_status(void)
{
	/* NUS, main and the motor worker share SAADC and monitor state. */
	k_mutex_lock(&power_lock, K_FOREVER);
	struct kr_status status = read_power();
	status.charge_current_ma = charge_configured ? KR_CHARGE_CURRENT_MA : -1;
	kr_power_update(&power_monitor, &status, k_uptime_get());
	k_mutex_unlock(&power_lock);
	return status;
}

static void render_led_locked(void)
{
	if (!led_ready) { return; }
	uint8_t color = led_stopped ? 0 : kr_status_led_rgb(led_charge, led_usb, led_error, led_diagnostic);
	if (led_error && (k_uptime_get() / 500) % 2) { color = 0; }
	for (size_t i = 0; i < ARRAY_SIZE(rgb); ++i) {
		(void)gpio_pin_set_dt(&rgb[i], (color & BIT(i)) != 0);
	}
}

int kr_board_led_ready(bool error)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	int result = 0;
	for (size_t i = 0; i < ARRAY_SIZE(rgb); ++i) {
		result = gpio_is_ready_dt(&rgb[i]) ? gpio_pin_configure_dt(&rgb[i], GPIO_OUTPUT_INACTIVE) : -ENODEV;
		if (result) { break; }
	}
	led_ready = !result;
	led_error = error;
	led_usb = kr_board_usb_present();
	render_led_locked();
	k_mutex_unlock(&led_lock);
	return result;
}

void kr_board_led(bool active)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	led_diagnostic = active;
	if (!led_ready && !led_stopped && gpio_is_ready_dt(&led)) { (void)gpio_pin_set_dt(&led, active); }
	render_led_locked();
	k_mutex_unlock(&led_lock);
}

void kr_board_led_error(bool error)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	led_error = error;
	render_led_locked();
	k_mutex_unlock(&led_lock);
}

void kr_board_charge_led(enum kr_charge_level level, bool usb)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	led_charge = level;
	led_usb = usb;
	render_led_locked();
	k_mutex_unlock(&led_lock);
}

void kr_board_led_off(void)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	led_stopped = true;
	for (size_t i = 0; i < ARRAY_SIZE(rgb); ++i) {
		if (gpio_is_ready_dt(&rgb[i])) { (void)gpio_pin_set_dt(&rgb[i], 0); }
	}
	k_mutex_unlock(&led_lock);
}

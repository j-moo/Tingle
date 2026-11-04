#include "board.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <zephyr/device.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/random/random.h>
#include <zephyr/storage/flash_map.h>
#include <hal/nrf_saadc.h>

static struct nvs_fs fs;
static struct kr_settings readback;
static const struct device *gpio = DEVICE_DT_GET(DT_NODELABEL(gpio0));
static const struct device *adc = DEVICE_DT_GET(DT_NODELABEL(adc));
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);
static bool battery_ready;
BUILD_ASSERT(sizeof(struct kr_settings) < 3900, "Atomic NVS record must fit one erase sector");

static int put(uint16_t key, const void *data, size_t length)
{
	ssize_t result = nvs_write(&fs, key, data, length);
	return result == (ssize_t)length || result == 0 ? 0 : result < 0 ? (int)result : -EIO;
}

static int save(void *ctx, const struct kr_settings *next)
{
	(void)ctx;
	int result = put(10, next, sizeof(*next));
	if (result) { return result; }
	ssize_t got = nvs_read(&fs, 10, &readback, sizeof(readback));
	if (got != sizeof(readback) || memcmp(next, &readback, sizeof(readback)) ||
		!kr_settings_valid(&readback)) { return -EIO; }
	return 0;
}

static const char *authorize(void *ctx, const struct kr_settings *next)
{
	(void)ctx;
	(void)next;
	/* S15P31E106-225 dependency: Notion sync-runs explicitly returns
	 * POLICY_UNRESOLVED until the official payload provider exists.
	 * No insecure runtime switch: replace this adapter only after agreement on
	 * authenticated owner/server envelope, schema and verification vectors.
	 * https://app.notion.com/p/3ffc91ccb66080d58b62dfbbf647b3b8 */
	return "POLICY_UNRESOLVED";
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
	core->platform = (struct kr_platform){.save = save, .authorize = authorize};
	if (gpio_is_ready_dt(&led)) { gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE); }
	/* Seeed: P0.14 LOW for VBAT measurement; never drive HIGH while charging.
	 * P0.17 is charge-status input only. HIGH is not a reliable 'full' signal.
	 * https://wiki.seeedstudio.com/XIAO_BLE/ */
	if (device_is_ready(gpio) && device_is_ready(adc)) {
		struct adc_channel_cfg channel = {.gain = ADC_GAIN_1_6, .reference = ADC_REF_INTERNAL,
			.acquisition_time = ADC_ACQ_TIME(ADC_ACQ_TIME_MICROSECONDS, 40),
			.channel_id = 0, .input_positive = NRF_SAADC_INPUT_AIN7};
		battery_ready = !gpio_pin_configure(gpio, 14, GPIO_OUTPUT_LOW) &&
			!gpio_pin_configure(gpio, 17, GPIO_INPUT) && !adc_channel_setup(adc, &channel);
	}
	const struct device *flash = FIXED_PARTITION_DEVICE(registration_store);
	int result = device_is_ready(flash) ? 0 : device_init(flash);
	if (result) { return result; }
	const struct flash_area *area;
	result = flash_area_open(FIXED_PARTITION_ID(registration_store), &area);
	if (result) { return result; }
	fs.flash_device = area->fa_dev;
	fs.offset = area->fa_off;
	fs.sector_size = 4096;
	fs.sector_count = area->fa_size / fs.sector_size;
	result = nvs_mount(&fs);
	flash_area_close(area);
	if (result) { return result; } /* Never auto-format, erase or rotate keys. */
	result = identity(&core->identity);
	if (result) { return result; }
	ssize_t got = nvs_read(&fs, 10, &core->current, sizeof(core->current));
	if (got == -ENOENT) { memset(&core->current, 0, sizeof(core->current)); }
	else if (got != sizeof(core->current) || !kr_settings_valid(&core->current)) { return -EILSEQ; }
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

struct kr_status kr_board_status(void)
{
	struct kr_status status = {.battery_mv = -1, .charging = -1};
	if (!battery_ready) { return status; }
	int charge = gpio_pin_get(gpio, 17);
	if (charge >= 0) { status.charging = !charge; }
	int16_t raw;
	struct adc_sequence sequence = {.channels = BIT(0), .buffer = &raw,
		.buffer_size = sizeof(raw), .resolution = 12};
	if (!adc_read(adc, &sequence) && raw > 0) {
		int32_t mv = raw;
		if (!adc_raw_to_millivolts(600, ADC_GAIN_1_6, 12, &mv)) {
			/* R16=1M, R17=510k. Voltage does not prove battery presence. */
			status.battery_mv = (mv * 1510 + 255) / 510;
		}
	}
	return status;
}

void kr_board_led(bool active)
{
	if (gpio_is_ready_dt(&led)) { gpio_pin_set_dt(&led, active); }
}

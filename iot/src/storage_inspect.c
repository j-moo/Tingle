/* Local USB-only inspection. Never mounts NVS, writes flash or prints record data. */
#include "board.h"
#include "carrier.h"
#include "registration.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <mbedtls/sha256.h>
#include <string.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/kernel.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/crc.h>

static void inspect_area(const struct device *flash, uint32_t start, uint32_t size, unsigned pass)
{
	uint32_t aligned[4096 / sizeof(uint32_t)];
	uint8_t *block = (uint8_t *)aligned;
	mbedtls_sha256_context hash;
	mbedtls_sha256_init(&hash);
	(void)mbedtls_sha256_starts(&hash, 0);
	unsigned records = 0, failed = 0;
	for (uint32_t base = 0; base < size; base += sizeof(aligned)) {
		int result = flash_read(flash, start + base, block, sizeof(aligned));
		if (result) { printk("INSPECT read error area=%x offset=%x result=%d\n", start, base, result); break; }
		(void)mbedtls_sha256_update(&hash, block, sizeof(aligned));
		for (int pos = 4080; pos >= 8; pos -= 8) {
			const uint8_t *ate = block + pos;
			bool erased = true;
			for (unsigned i = 0; i < 8; ++i) { erased &= ate[i] == 0xff; }
			if (erased) { break; }
			uint16_t id = sys_get_le16(ate), off = sys_get_le16(ate + 2), len = sys_get_le16(ate + 4);
			if (crc8_ccitt(0xff, ate, 7) != ate[7] || (uint32_t)off + len >= 4088) { continue; }
			if (id == 0xffff || !len) { continue; }
			++records;
			bool valid = len >= 4 && crc32_ieee(block + off, len - 4) == sys_get_le32(block + off + len - 4);
			failed += !valid;
			if (start == 0x40000 || !valid) {
				printk("INSPECT pass=%u area=%x sector=%u ate=%x id=%u length=%u crc=%s\n",
					pass, start, base / 4096, pos, id, len, valid ? "OK" : "BAD");
				k_msleep(20); /* Let the USB logger drain; no record bytes are logged. */
			}
			if (start == 0x40000 && id == 10 && len == sizeof(struct kr_settings) + 4) {
				struct kr_settings settings;
				memcpy(&settings, block + off, sizeof(settings));
				uint8_t digest[32]; char hex[65];
				kr_hash(&settings, sizeof(settings), digest); kr_hex(digest, sizeof(digest), hex);
				printk("INSPECT SETTINGS version=%lld bytes=%u semantic=%s dataHash=%s\n",
					(long long)settings.version, settings.payload_size, kr_settings_valid(&settings) ? "OK" : "BAD", hex);
				printk("INSPECT SETTINGS crcStored=%08x crcComputed=%08x\n",
					sys_get_le32(block + off + len - 4), crc32_ieee(block + off, len - 4));
				if (!valid) {
					/* Diagnose a single-bit read/storage error in RAM only. */
					uint32_t expected = sys_get_le32(block + off + len - 4);
					unsigned candidates = 0;
					for (size_t byte = 0; byte < sizeof(settings); ++byte) {
						for (unsigned bit = 0; bit < 8; ++bit) {
							((uint8_t *)&settings)[byte] ^= BIT(bit);
							if (crc32_ieee((const uint8_t *)&settings, sizeof(settings)) == expected) {
								++candidates;
								printk("INSPECT single-bit candidate offset=%u bit=%u semantic=%s\n",
									(unsigned)byte, bit, kr_settings_valid(&settings) ? "OK" : "BAD");
							}
							((uint8_t *)&settings)[byte] ^= BIT(bit);
						}
						if (!(byte % 64)) { k_msleep(1); }
					}
					printk("INSPECT single-bit candidates=%u; no data changed\n", candidates);
				}
				crypto_wipe(&settings, sizeof(settings));
				k_msleep(40);
			}
			if (start == 0x40000 && id == 1 && len == sizeof(struct kr_identity) + 4) {
				struct kr_identity identity;
				memcpy(&identity, block + off, sizeof(identity));
				uint8_t seed[32], secret[64], public_key[32], hardware[8];
				char expected[65] = "XIAO-";
				bool hardware_ok = hwinfo_get_device_id(hardware, sizeof(hardware)) == sizeof(hardware);
				if (hardware_ok) { kr_hex(hardware, sizeof(hardware), expected + 5); }
				memcpy(seed, identity.secret, sizeof(seed));
				crypto_ed25519_key_pair(secret, public_key, seed);
				bool key_ok = !crypto_verify64(secret, identity.secret) && !crypto_verify32(public_key, identity.public_key);
				bool serial_ok = hardware_ok && !identity.serial[64] && !strcmp(identity.serial, expected);
				printk("INSPECT identity keypair=%s hardware=%s\n", key_ok ? "OK" : "BAD", serial_ok ? "OK" : "BAD");
				if (key_ok && serial_ok && !identity.key_id[64] && kr_identifier(identity.key_id)) {
					kr_board_print_identity(&identity);
				}
				crypto_wipe(&identity, sizeof(identity));
				crypto_wipe(secret, sizeof(secret));
				crypto_wipe(seed, sizeof(seed));
			}
		}
	}
	uint8_t digest[32]; char hex[65];
	(void)mbedtls_sha256_finish(&hash, digest);
	mbedtls_sha256_free(&hash);
	kr_hex(digest, sizeof(digest), hex);
	printk("INSPECT pass=%u area=%x bytes=%u sha256=%s records=%u bad=%u\n", pass, start, size, hex, records, failed);
	k_msleep(50);
	crypto_wipe(aligned, sizeof(aligned));
}

void kr_storage_inspect(void)
{
	const struct device *console = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
	uint32_t dtr = 0;
	while (uart_line_ctrl_get(console, UART_LINE_CTRL_DTR, &dtr) || !dtr) { k_msleep(100); }
	uint8_t hardware[8]; char serial[17];
	if (hwinfo_get_device_id(hardware, sizeof(hardware)) == sizeof(hardware)) {
		kr_hex(hardware, sizeof(hardware), serial);
		printk("INSPECT %s hardware=XIAO-%s read-only; no NVS mount/write/erase\n", KR_FIRMWARE_VERSION, serial);
	}
	const struct device *flash = FIXED_PARTITION_DEVICE(registration_store);
	int result = device_is_ready(flash) ? 0 : device_init(flash);
	if (result) { printk("INSPECT flash init=%d\n", result); return; }
	(void)kr_board_led_ready(false);
	for (unsigned pass = 1; pass <= 2; ++pass) {
		inspect_area(flash, FIXED_PARTITION_OFFSET(registration_store), FIXED_PARTITION_SIZE(registration_store), pass);
		inspect_area(flash, FIXED_PARTITION_OFFSET(keyring_store), FIXED_PARTITION_SIZE(keyring_store), pass);
	}
	for (unsigned trial = 0; trial < 8; ++trial) {
		int16_t samples[8] = {0};
		struct adc_sequence_options options = {.extra_samplings = 7, .interval_us = trial < 4 ? 100 : 0};
		struct adc_sequence sequence = {.channels = BIT(0), .buffer = samples, .buffer_size = sizeof(samples),
			.resolution = 12, .options = &options};
		int error = adc_read(DEVICE_DT_GET(DT_NODELABEL(adc)), &sequence);
		printk("INSPECT ADC trial=%u interval_us=%u result=%d\n", trial, options.interval_us, error);
		k_msleep(250);
	}
	printk("INSPECT done; USB command LED TEST runs bounded RGB test, no motor override\n");
	char line[24]; size_t used = 0; int64_t next = 0;
	for (;;) {
		unsigned char c;
		while (!uart_poll_in(console, &c)) {
			if (c == '\n') {
				line[used] = 0;
				if (!strcmp(line, "LED TEST") || !strcmp(line, "LED TEST\r")) {
					for (unsigned color = 1; color <= 4; color <<= 1) {
						bool started = kr_carrier_feedback_start((struct kr_feedback_options){.rgb = color});
						printk("INSPECT LED rgb=%u started=%d\n", color, started);
						k_msleep(1000);
						kr_carrier_feedback_stop();
					}
				}
				used = 0;
			} else if (used < sizeof(line) - 1) { line[used++] = c; }
		}
		if (k_uptime_get() >= next) {
			struct kr_status power = kr_board_status();
			printk("INSPECT POWER mv=%d usb=%d charging=%d state=%s charge=%s\n",
				power.battery_mv, power.usb_present, power.charging, power.battery_power_state, power.charge_monitor_state);
			next = k_uptime_get() + 2000;
		}
		(void)kr_carrier_poll_charge();
		k_msleep(50);
	}
}

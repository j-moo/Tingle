#include "flash_guard.h"
#include <errno.h>
#include <string.h>

int kr_flash_write_verified(const struct kr_flash_io *io, uint32_t address,
	const void *data, size_t length, uint32_t *failed_address)
{
	uint32_t verification[16]; /* Aligned, 64-byte read buffer, no heap. */
	const uint8_t *bytes = data;
	if (failed_address) { *failed_address = address; }
	if (!length) { return 0; }
	if (!io || !io->write || !io->read || !data || address % 4 || length % 4 ||
		length > UINT32_MAX - address) { return -EINVAL; }
	while (length) {
		size_t chunk = 256 - address % 256;
		if (chunk > length) { chunk = length; }
		if (failed_address) { *failed_address = address; }
		int result = io->write(io->ctx, address, bytes, chunk);
		if (result) { return result; }
		/* A success return alone is insufficient. Do not let NVS publish its
		 * metadata after a silently omitted program page. No arbitrary delay. */
		for (size_t offset = 0; offset < chunk; offset += sizeof(verification)) {
			size_t n = chunk - offset; if (n > sizeof(verification)) { n = sizeof(verification); }
			if (failed_address) { *failed_address = address + (uint32_t)offset; }
			result = io->read(io->ctx, address + (uint32_t)offset, verification, n);
			if (result) { return result; }
			if (memcmp(verification, bytes + offset, n)) { return -EIO; }
		}
		address += (uint32_t)chunk; bytes += chunk; length -= chunk;
	}
	return 0;
}

#ifndef KR_HOST
#include <zephyr/device.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/drivers/clock_control/nrf_clock_control.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/onoff.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/sys/printk.h>

static const struct device *physical(void) { return FIXED_PARTITION_DEVICE(registration_store); }
static int physical_write(void *ctx, uint32_t address, const void *bytes, size_t length)
{ (void)ctx; return flash_write(physical(), address, bytes, length); }
static int physical_read(void *ctx, uint32_t address, void *bytes, size_t length)
{ (void)ctx; return flash_read(physical(), address, bytes, length); }
static const struct kr_flash_io physical_io = {.write = physical_write, .read = physical_read};
K_MUTEX_DEFINE(flash_access);
static int begin_access(struct onoff_client *client)
{
	/* nRF52840 anomaly 244: a HFINT/HFXO transition during QSPI corrupts
	 * transfers. Hold the shared clock reference across write AND readback.
	 * Do not directly toggle clock registers owned by the BLE controller. */
	k_mutex_lock(&flash_access, K_FOREVER);
	struct onoff_manager *manager = z_nrf_clock_control_get_onoff(CLOCK_CONTROL_NRF_SUBSYS_HF);
	if (!manager) { k_mutex_unlock(&flash_access); return -ENODEV; }
	sys_notify_init_spinwait(&client->notify);
	int result = onoff_request(manager, client);
	if (result < 0) { k_mutex_unlock(&flash_access); return result; }
	int64_t deadline = k_uptime_get() + 1000;
	int completed = 0;
	while ((result = sys_notify_fetch_result(&client->notify, &completed)) == -EAGAIN) {
		if (k_uptime_get() >= deadline) {
			(void)onoff_cancel_or_release(manager, client);
			k_mutex_unlock(&flash_access); return -ETIMEDOUT;
		}
		k_usleep(100); /* Wait for the oscillator, not an application queue delay. */
	}
	if (result < 0 || completed < 0) {
		(void)onoff_cancel_or_release(manager, client);
		k_mutex_unlock(&flash_access); return result < 0 ? result : completed;
	}
	return 0;
}
static void end_access(void)
{
	(void)onoff_release(z_nrf_clock_control_get_onoff(CLOCK_CONTROL_NRF_SUBSYS_HF));
	k_mutex_unlock(&flash_access);
}
static bool valid_range(off_t offset, size_t length)
{
	/* Only the existing two IoT regions use this adapter; BLE bond/internal
	 * application flash and every other external region remain untouched. */
	return offset >= 0 && (uint64_t)offset <= 0x60000 && length <= 0x60000 - (uint64_t)offset;
}
static int guarded_read(const struct device *dev, off_t offset, void *bytes, size_t length)
{
	(void)dev; if (!valid_range(offset, length)) { return -EINVAL; }
	struct onoff_client clock;
	int result = begin_access(&clock); if (result) { return result; }
	result = flash_read(physical(), offset, bytes, length);
	end_access(); return result;
}
static int guarded_write(const struct device *dev, off_t offset, const void *bytes, size_t length)
{
	(void)dev; if (!valid_range(offset, length)) { return -EINVAL; }
	struct onoff_client clock;
	int result = begin_access(&clock); if (result) { return result; }
	uint32_t failed;
	result = kr_flash_write_verified(&physical_io, (uint32_t)offset, bytes, length, &failed);
	end_access();
	if (result) { printk("FLASH VERIFY failed address=%x bytes=%u result=%d; NVS commit aborted\n", failed, (unsigned)length, result); }
	return result;
}
static int guarded_erase(const struct device *dev, off_t offset, size_t length)
{
	(void)dev; if (!valid_range(offset, length)) { return -EINVAL; }
	struct onoff_client clock;
	int result = begin_access(&clock); if (result) { return result; }
	result = flash_erase(physical(), offset, length);
	uint32_t words[16];
	for (size_t pos = 0; !result && pos < length; pos += sizeof(words)) {
		size_t count = length - pos; if (count > sizeof(words)) { count = sizeof(words); }
		result = flash_read(physical(), offset + (off_t)pos, words, count);
		for (size_t i = 0; !result && i < count; ++i) {
			if (((uint8_t *)words)[i] != 0xff) { result = -EIO; }
		}
	}
	end_access(); return result;
}
static const struct flash_parameters *parameters(const struct device *dev)
{ (void)dev; return flash_get_parameters(physical()); }
static int size(const struct device *dev, uint64_t *out)
{ (void)dev; return flash_get_size(physical(), out); }
static void layout(const struct device *dev, const struct flash_pages_layout **out, size_t *count)
{
	(void)dev;
	const struct flash_driver_api *api = physical()->api;
	api->page_layout(physical(), out, count);
}
static DEVICE_API(flash, guarded_api) = {
	.read = guarded_read, .write = guarded_write, .erase = guarded_erase,
	.get_parameters = parameters, .get_size = size, .page_layout = layout,
};
DEVICE_DEFINE(kr_verified_flash, "tingle-verified-flash", NULL, NULL, NULL, NULL,
	POST_KERNEL, CONFIG_KERNEL_INIT_PRIORITY_DEVICE, &guarded_api);
const struct device *kr_flash_guard_device(void) { return DEVICE_GET(kr_verified_flash); }
#endif

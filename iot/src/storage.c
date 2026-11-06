#include "storage.h"
#include <errno.h>
#include <zephyr/device.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/storage/flash_map.h>

static struct nvs_fs fs;
static bool mounted;
BUILD_ASSERT(sizeof(struct kr_record) < 3900, "NVS record must fit one sector");
BUILD_ASSERT(CONFIG_NORDIC_QSPI_NOR_FLASH_LAYOUT_PAGE_SIZE == 4096,
	"QSPI page layout must match the 4 KiB NVS sector");

static int write_value(uint16_t id, const void *value, size_t len)
{
	if (!mounted) {
		return -EIO;
	}
	ssize_t written = nvs_write(&fs, id, value, len);
	/* nvs_write returns 0 for an already identical durable value. */
	return written == (ssize_t)len || written == 0 ? 0 : (int)written;
}

int kr_storage_config(void *ctx, const struct kr_config *config)
{
	ARG_UNUSED(ctx);
	return write_value(1, config, sizeof(*config));
}

int kr_storage_record(void *ctx, unsigned slot, const struct kr_record *record)
{
	ARG_UNUSED(ctx);
	if (slot >= KR_RECORDS || !mounted) {
		return -EINVAL;
	}
	if (record->kind == KR_EMPTY) {
		return nvs_delete(&fs, (uint16_t)(100 + slot));
	}
	return write_value((uint16_t)(100 + slot), record, sizeof(*record));
}

int kr_storage_suppression(void *ctx, unsigned slot, const struct kr_suppression *s)
{
	ARG_UNUSED(ctx);
	if (slot >= KR_SUPPRESSIONS || !mounted) {
		return -EINVAL;
	}
	if (!s->peer) {
		return nvs_delete(&fs, (uint16_t)(200 + slot));
	}
	return write_value((uint16_t)(200 + slot), s, sizeof(*s));
}

static int restore(uint16_t id, void *value, size_t len)
{
	ssize_t result = nvs_read(&fs, id, value, len);
	if (result == -ENOENT) {
		return 0;
	}
	return result == (ssize_t)len ? 0 : -EILSEQ;
}

static int remove_value(uint16_t id)
{
	int result = nvs_delete(&fs, id);
	if (result < 0 && result != -ENOENT) { return result; }
	uint8_t byte;
	return nvs_read(&fs, id, &byte, sizeof(byte)) == -ENOENT ? 0 : -EIO;
}

int kr_storage_clear_owner(struct kr_engine *e)
{
	if (!mounted) { return -EIO; }
	int result = remove_value(1);
	for (unsigned i = 0; !result && i < KR_RECORDS; ++i) {
		result = remove_value((uint16_t)(100 + i));
	}
	for (unsigned i = 0; !result && i < KR_SUPPRESSIONS; ++i) {
		result = remove_value((uint16_t)(200 + i));
	}
	if (!result) {
		uint64_t device = e->config.device;
		struct kr_io io = e->io;
		kr_init(e, device, io);
	}
	return result;
}

int kr_storage_open(struct kr_engine *e)
{
	const struct device *flash = FIXED_PARTITION_DEVICE(keyring_store);
	int result = device_is_ready(flash) ? 0 : device_init(flash);
	if (result) {
		return result;
	}
	const struct flash_area *area;
	result = flash_area_open(FIXED_PARTITION_ID(keyring_store), &area);
	if (result) {
		return result;
	}
	fs.flash_device = area->fa_dev;
	fs.offset = area->fa_off;
	fs.sector_size = 4096;
	fs.sector_count = area->fa_size / fs.sector_size;
	result = nvs_mount(&fs);
	flash_area_close(area);
	if (result) {
		return result; /* Never automatically erase a failed/corrupt store. */
	}
	mounted = true;
	uint64_t hardware_id = e->config.device;
	result = restore(1, &e->config, sizeof(e->config));
	for (unsigned i = 0; !result && i < KR_RECORDS; ++i) {
		result = restore((uint16_t)(100 + i), &e->records[i], sizeof(e->records[i]));
	}
	for (unsigned i = 0; !result && i < KR_SUPPRESSIONS; ++i) {
		result = restore((uint16_t)(200 + i), &e->suppressed[i], sizeof(e->suppressed[i]));
	}
	if (!result && (hardware_id != e->config.device || !kr_restored_valid(e))) {
		result = -EILSEQ;
	}
	return result;
}

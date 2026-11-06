#include "storage.h"
#include <errno.h>
#include <string.h>
#include <zephyr/device.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/random/random.h>

static struct nvs_fs fs;
static bool mounted;
static uint8_t verification[sizeof(struct kr_record)];
static int remove_value(uint16_t id);
static int write_value(uint16_t id, const void *value, size_t len);
int kr_storage_sequence(void *ctx, uint32_t next)
{
	ARG_UNUSED(ctx); if (!next) { return -EINVAL; }
	uint8_t bytes[8]; memcpy(bytes, "KSQ1", 4); kr_put32(bytes + 4, next);
	return write_value(2, bytes, sizeof(bytes));
}
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
	if (written != (ssize_t)len && written != 0) { return (int)written; }
	if (len > sizeof(verification) || nvs_read(&fs, id, verification, sizeof(verification)) != (ssize_t)len ||
		memcmp(verification, value, len)) { return -EIO; }
	return 0;
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
		return remove_value((uint16_t)(100 + slot));
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
		return remove_value((uint16_t)(200 + slot));
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
	for (unsigned i = 0; !result && i < KR_RECORDS; ++i) { result = remove_value((uint16_t)(300 + i)); }
	for (unsigned i = 0; !result && i < 16; ++i) { result = remove_value((uint16_t)(500 + i)); }
	for (unsigned i = 0; !result && i < KR_SUPPRESSIONS; ++i) { result = remove_value((uint16_t)(800 + i)); }
	for (unsigned i = 0; !result && i < KR_SUPPRESSIONS; ++i) { result = remove_value((uint16_t)(900 + i)); }
	if (!result) {
		uint64_t device = e->config.device;
		uint32_t next = e->next_sequence; uint8_t cursor[16]; memcpy(cursor, e->cursor_epoch, 16);
		struct kr_io io = e->io;
		kr_init(e, device, io);
		e->next_sequence = next; memcpy(e->cursor_epoch, cursor, 16);
	}
	return result;
}

int kr_storage_blob(void *ctx, uint16_t key, void *data, size_t length, bool write)
{
	ARG_UNUSED(ctx);
	if (!mounted || !((key >= 300 && key < 332) || (key >= 500 && key < 516) || (key >= 800 && key < 864) ||
		(key >= 900 && key < 964))) { return -EINVAL; }
	if (write) { return length ? write_value(key, data, length) : remove_value(key); }
	ssize_t count = nvs_read(&fs, key, data, length);
	return count == (ssize_t)length ? 0 : count < 0 ? (int)count : -EILSEQ;
}
int kr_storage_space(void *ctx, int64_t *total, int64_t *used)
{
	ARG_UNUSED(ctx); if (!mounted) { return -EIO; }
	ssize_t free = nvs_calc_free_space(&fs); if (free < 0) { return (int)free; }
	*total = (int64_t)fs.sector_count * fs.sector_size;
	if (free > *total) { return -EILSEQ; } *used = *total - free; return 0;
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
	if (!result) {
		uint8_t counter[8]; ssize_t got = nvs_read(&fs, 2, counter, sizeof(counter));
		if (got != -ENOENT && (got != 8 || memcmp(counter, "KSQ1", 4) || !kr_get32(counter + 4))) { return -EILSEQ; }
		if (got == 8 && kr_get32(counter + 4) > e->next_sequence) { e->next_sequence = kr_get32(counter + 4); }
		result = kr_storage_sequence(NULL, e->next_sequence);
		got = nvs_read(&fs, 3, e->cursor_epoch, sizeof(e->cursor_epoch));
		if (!result && got == -ENOENT) {
			result = sys_csrand_get(e->cursor_epoch, 16);
			if (!result) { result = write_value(3, e->cursor_epoch, 16); }
		} else if (got != 16) { result = -EILSEQ; }
		uint8_t empty[16] = {0}; if (!result && !memcmp(e->cursor_epoch, empty, 16)) { result = -EILSEQ; }
	}
	return result;
}

#include "flash_guard.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

struct simulated_flash { uint8_t data[4096]; unsigned calls; int omit, read_error, write_error; };
static int write_page(void *ctx, uint32_t at, const void *bytes, size_t n)
{
	struct simulated_flash *f = ctx;
	assert(n && at + n <= sizeof(f->data) && at / 256 == (at + n - 1) / 256);
	++f->calls;
	if (f->write_error == (int)f->calls) { return -ETIMEDOUT; }
	if (f->omit != (int)f->calls) { memcpy(f->data + at, bytes, n); }
	return 0;
}
static int read_page(void *ctx, uint32_t at, void *bytes, size_t n)
{
	struct simulated_flash *f = ctx;
	if (f->read_error == (int)f->calls) { return -EIO; }
	memcpy(bytes, f->data + at, n); return 0;
}
int main(void)
{
	uint8_t payload[2324]; memset(payload, 0, sizeof(payload));
	struct simulated_flash f = {0}; uint32_t failed;
	const struct kr_flash_io io = {.ctx = &f, .write = write_page, .read = read_page};
	for (unsigned start = 0; start < 256; start += 4) {
		memset(&f, 0, sizeof(f)); memset(f.data, 0xff, sizeof(f.data));
		assert(!kr_flash_write_verified(&io, start, payload, sizeof(payload), &failed));
		assert(!memcmp(f.data + start, payload, sizeof(payload)));
		unsigned calls = f.calls;
		for (unsigned page = 1; page <= calls; ++page) {
			memset(&f, 0, sizeof(f)); memset(f.data, 0xff, sizeof(f.data)); f.omit = (int)page;
			assert(kr_flash_write_verified(&io, start, payload, sizeof(payload), &failed) == -EIO);
			assert(f.calls == page); /* Never continue to later pages or metadata. */
		}
	}
	memset(&f, 0, sizeof(f)); f.write_error = 1;
	assert(kr_flash_write_verified(&io, 0, payload, sizeof(payload), &failed) == -ETIMEDOUT);
	f.write_error = 0; f.calls = 0; f.read_error = 1;
	assert(kr_flash_write_verified(&io, 0, payload, sizeof(payload), &failed) == -EIO);
	assert(kr_flash_write_verified(&io, 1, payload, sizeof(payload), &failed) == -EINVAL);
	assert(kr_flash_write_verified(&io, UINT32_MAX - 3, payload, 8, &failed) == -EINVAL);
	puts("flash guard: 64 alignments, every omitted page, read/write failures, overflow and fail-before-next-page passed");
	return 0;
}

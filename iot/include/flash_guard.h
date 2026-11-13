#ifndef TINGLE_FLASH_GUARD_H
#define TINGLE_FLASH_GUARD_H
#include <stddef.h>
#include <stdint.h>
struct kr_flash_io {
	void *ctx;
	int (*write)(void *, uint32_t, const void *, size_t);
	int (*read)(void *, uint32_t, void *, size_t);
};
/* The caller owns allocation/erase. This never erases or repairs old data. */
int kr_flash_write_verified(const struct kr_flash_io *, uint32_t address,
	const void *data, size_t length, uint32_t *failed_address);
#ifndef KR_HOST
struct device;
const struct device *kr_flash_guard_device(void);
#endif
#endif

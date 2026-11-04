#include "registration.h"
#ifdef KR_HOST
#include <tinycrypt/sha256.h>
#include <assert.h>
void kr_hash(const void *data, size_t len, uint8_t out[32])
{
	struct tc_sha256_state_struct hash;
	int ok = tc_sha256_init(&hash) && tc_sha256_update(&hash, data, len) && tc_sha256_final(out, &hash);
	assert(ok);
}
#else
#include <mbedtls/sha256.h>
#include <zephyr/sys/__assert.h>
void kr_hash(const void *data, size_t len, uint8_t out[32])
{
	int result = mbedtls_sha256(data, len, out, 0);
	__ASSERT_NO_MSG(result == 0);
}
#endif

#ifndef TINGLE_PROFILE_H
#define TINGLE_PROFILE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Local storage candidate, never a peer advertisement or a matching approval. */
#define KR_PROFILE_STORAGE_FORMAT "tingle-profile-storage-v1"
#define KR_PROFILE_SURVEY "SIS-Q-1.1-100"
#define KR_PROFILE_CODEBOOK_SHA256 "ee4357d4248e89619845e7ca99ad30542e37d0766454e65a2de5d85c12cba641"
#define KR_PROFILE_STORAGE_BYTES 196

struct kr_profile_data {
	const uint8_t *traits;
	const uint8_t *actual;
	const uint8_t *wish_traits;
	const uint8_t *wishes;
	const uint8_t *priority;
	const uint8_t *strict;
	const uint8_t *tolerance;
	const uint8_t *personal;
	uint8_t options; /* matching, vibration, LED; no synthetic engine activation */
};

/* Pointers borrow the complete, validated payload. No allocation or rounding. */
bool kr_profile_data_decode(const uint8_t *bytes, size_t length, struct kr_profile_data *out);

#endif

#ifndef TINGLE_POLICY_H
#define TINGLE_POLICY_H
#include "profile.h"
#define KR_POLICY_FORMAT "tingle-keyring-settings-v3"
#define KR_POLICY_LEGACY_FORMAT "tingle-keyring-settings-v2"
#define KR_POLICY_BASE 487
#define KR_RESTRICTION_BYTES 25
#define KR_RESTRICTIONS_MAX 60
struct kr_policy {
	const uint8_t *profile, *grant, *restrictions;
	uint64_t valid_until;
	uint32_t policy, rule, presence_seconds, opportunity_seconds;
	uint16_t count;
	bool complete, enabled;
};
bool kr_policy_decode(const uint8_t *bytes, size_t length, struct kr_policy *out);
bool kr_policy_format(const char *format);
bool kr_policy_allows(const struct kr_policy *policy, const uint8_t owner_ref[16], uint64_t now);
#endif

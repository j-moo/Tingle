#include "policy.h"
#include "matching.h"
#include <string.h>
static uint32_t u32(const uint8_t *p) { return p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static uint64_t u64(const uint8_t *p) { return u32(p) | (uint64_t)u32(p + 4) << 32; }
static bool nonzero(const uint8_t *p, size_t n) { unsigned v = 0; for (size_t i = 0; i < n; ++i) { v |= p[i]; } return v != 0; }
bool kr_policy_format(const char *format)
{ return !strcmp(format, KR_POLICY_FORMAT) || !strcmp(format, KR_POLICY_LEGACY_FORMAT); }
bool kr_policy_decode(const uint8_t *b, size_t length, struct kr_policy *out)
{
	if (!b || !out || length < KR_POLICY_BASE || (memcmp(b, "TSV2", 4) && memcmp(b, "TSV3", 4))) { return false; }
	bool version3 = b[3] == '3';
	struct kr_profile_data data;
	if (!kr_profile_data_decode(b + 4, 196, &data) || b[200] != (version3 ? 4 : 3) ||
		!nonzero(b + 293, 16) || b[481] > 1 || b[482] > 1) { return false; }
	struct kr_policy p = {.profile = b + 4, .grant = b + 200, .policy = u32(b + 461), .rule = u32(b + 465),
		.valid_until = u64(b + 469), .count = (uint16_t)(b[477] | (unsigned)b[478] << 8),
		.presence_seconds = (uint32_t)b[479] | (uint32_t)b[480] << 8,
		.complete = b[481] != 0, .enabled = b[482] != 0, .restrictions = b + KR_POLICY_BASE};
	p.opportunity_seconds = version3 ? u32(b + 483) : 7 * 86400;
	if (p.count > KR_RESTRICTIONS_MAX || length != KR_POLICY_BASE + (size_t)p.count * KR_RESTRICTION_BYTES ||
		p.policy != KR_MATCH_POLICY || p.rule != KR_MATCH_RULE || p.valid_until > INT64_MAX ||
		p.valid_until != u64(p.grant + 117) || p.policy != u32(p.grant + 109) || p.rule != u32(p.grant + 113) ||
		p.presence_seconds < 1 || p.presence_seconds > 300 || !p.opportunity_seconds || p.opportunity_seconds > 365 * 86400 ||
		(version3 ? u32(p.grant + 25) != p.opportunity_seconds : u32(b + 483) != 0)) { return false; }
	for (unsigned i = 0; i < p.count; ++i) {
		const uint8_t *r = p.restrictions + i * KR_RESTRICTION_BYTES;
		if (!nonzero(r, 16) || (r[16] != 1 && r[16] != 2) ||
			(r[16] == 1 ? !u64(r + 17) || u64(r + 17) > INT64_MAX : u64(r + 17) != 0)) { return false; }
		for (unsigned j = 0; j < i; ++j) {
			if (!memcmp(r, p.restrictions + j * KR_RESTRICTION_BYTES, 16)) { return false; }
		}
	}
	*out = p; return true;
}
bool kr_policy_allows(const struct kr_policy *p, const uint8_t owner[16], uint64_t now)
{
	if (!p->complete || !p->enabled || !now || now >= p->valid_until || !memcmp(owner, p->grant + 93, 16)) { return false; }
	for (unsigned i = 0; i < p->count; ++i) {
		const uint8_t *r = p->restrictions + i * KR_RESTRICTION_BYTES;
		if (!memcmp(r, owner, 16) && (r[16] == 2 || now < u64(r + 17))) { return false; }
	}
	return true;
}

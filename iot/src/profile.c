#include "profile.h"
#include <string.h>

static uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | (uint16_t)p[1] << 8); }

static unsigned bits(unsigned value)
{
	unsigned count = 0;
	for (; value; value >>= 1) { count += value & 1u; }
	return count;
}

static unsigned state(const uint8_t *p, unsigned index)
{
	return (p[index / 4] >> (2 * (index % 4))) & 3u;
}

static bool canonical_states(const uint8_t *p, unsigned count)
{
	unsigned used = (count % 4) * 2;
	return !used || !(p[count / 4] >> used);
}

static bool field(unsigned value, unsigned status, unsigned maximum,
	bool bitmap, unsigned max_selected, bool allow_any)
{
	if (status != 0) { return value == 0 && (status != 2 || allow_any); }
	return bitmap ? value && !(value & ~maximum) && bits(value) <= max_selected :
		value >= 1 && value <= maximum;
}

/* Frozen Q030..Q060 and Q065..Q095 codebook. A negative limit is a bitmap. */
static const int8_t actual_limits[31] = {
	5,5,5,5,5,5,5,5,5,5,-31,5,5,5,5,5,5,5,5,5,5,5,-31,5,5,5,7,5,3,5,4
};
static const int8_t wish_limits[31] = {
	5,5,5,5,5,5,5,5,5,5,-31,5,5,5,-31,-31,5,5,5,-31,-31,5,-31,-31,5,-31,-127,5,-7,5,-15
};

static bool range(const uint8_t *p, unsigned count, const int8_t *limits, bool wishes)
{
	const uint8_t *states = p + count;
	if (!canonical_states(states, count)) { return false; }
	for (unsigned i = 0; i < count; ++i) {
		int limit = limits ? limits[i] : 3;
		unsigned selected = !wishes && i == 10 ? 2 : 8;
		if (!field(p[i], state(states, i), (unsigned)(limit < 0 ? -limit : limit),
			limit < 0, selected, wishes)) { return false; }
	}
	return true;
}

static bool personal(const uint8_t *p)
{
	/* age:u16, gender, desired genders, photo, photo filter, smoking, drinking,
	 * religion, 8*2bit states:u16, age mode, desired min/max:u16. */
	unsigned age = u16(p), status = state(p + 9, 0);
	if (status == 2 || (status == 0 ? age < 19 : age != 0)) { return false; }
	static const uint8_t maxima[7] = {2,3,1,1,4,6,6};
	for (unsigned i = 0; i < 7; ++i) {
		status = state(p + 9, i + 1);
		unsigned value = p[i + 2];
		if (i == 2 || i == 3) {
			if (status == 2 || (status ? value != 0 : value > 1)) { return false; }
		} else if (!field(value, status, maxima[i], i == 1, 2, i == 1)) { return false; }
	}
	unsigned mode = p[11], min = u16(p + 12), max = u16(p + 14);
	return mode <= 3 && (mode == 3 ? min >= 19 && (!max || max >= min) : !min && !max);
}

bool kr_profile_data_decode(const uint8_t *bytes, size_t length, struct kr_profile_data *out)
{
	static const uint8_t tags[] = {1,0x10,0x11,0x20,0x21,0x30,0x31,0x32,0x40,0x41};
	static const uint8_t lengths[] = {46,9,39,5,39,3,2,2,16,1};
	static const uint8_t codebook[32] = {
		0xee,0x43,0x57,0xd4,0x24,0x8e,0x89,0x61,0x98,0x45,0xe7,0xca,0x99,0xad,0x30,0x54,
		0x2e,0x37,0xd0,0x76,0x64,0x54,0xe6,0x5a,0x2d,0xe5,0xd8,0x5c,0x12,0xcb,0xa6,0x41
	};
	if (!bytes || !out || length != KR_PROFILE_STORAGE_BYTES || memcmp(bytes, "TPS1", 4)) {
		return false;
	}
	struct kr_profile_data decoded = {0};
	const uint8_t *groups[10];
	size_t offset = 4;
	for (unsigned i = 0; i < 10; ++i) {
		if (offset + 3 > length || bytes[offset] != tags[i] ||
			u16(bytes + offset + 1) != lengths[i] || offset + 3 + lengths[i] > length) { return false; }
		groups[i] = bytes + offset + 3;
		offset += 3 + lengths[i];
	}
	if (offset != length || groups[0][0] != 1 ||
		memcmp(groups[0] + 1, KR_PROFILE_SURVEY, 13) || memcmp(groups[0] + 14, codebook, 32)) {
		return false;
	}
	for (unsigned i = 0; i < 4; ++i) {
		unsigned value = u16(groups[1] + 2 * i), status = state(groups[1] + 8, i);
		if (status == 2 || (status ? value != 0 : value > 10000)) { return false; }
	}
	if (!range(groups[2], 31, actual_limits, false) || !range(groups[3], 4, NULL, true) ||
		!range(groups[4], 31, wish_limits, true) || groups[5][0] > 3 || groups[6][0] > 3 ||
		!field(u16(groups[5] + 1), groups[5][0], 511, true, 3, true) ||
		!field(groups[6][1], groups[6][0], 31, true, 2, true) || groups[7][0] > 3 ||
		!field(groups[7][1], groups[7][0], 3, false, 0, false) ||
		!personal(groups[8]) || groups[9][0] > 7) { return false; }
	decoded.traits = groups[1]; decoded.actual = groups[2]; decoded.wish_traits = groups[3];
	decoded.wishes = groups[4]; decoded.priority = groups[5]; decoded.strict = groups[6];
	decoded.tolerance = groups[7]; decoded.personal = groups[8]; decoded.options = groups[9][0];
	*out = decoded;
	return true;
}

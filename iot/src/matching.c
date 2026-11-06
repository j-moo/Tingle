#include "matching.h"
#include <string.h>

static unsigned u16(const uint8_t *p) { return p[0] | (unsigned)p[1] << 8; }
static unsigned state(const uint8_t *p, unsigned i) { return (p[i / 4] >> (2 * (i % 4))) & 3u; }
static double min(double a, double b) { return a < b ? a : b; }
static double max(double a, double b) { return a > b ? a : b; }
static bool numeric(unsigned q)
{
	return q <= 39 || (q >= 41 && q <= 43) || (q >= 46 && q <= 48) ||
		q == 51 || q == 54 || q == 57 || q == 59;
}
static unsigned domain(unsigned q)
{
	if (q <= 80) { return (q - 61) / 4; }
	if (q <= 82) { return 5; }
	if (q <= 85) { return 6; }
	return q == 92 || q == 93 ? 8 : 7;
}
static int age(const uint8_t *a, const uint8_t *b)
{
	if (state(b + 9, 0) || a[11] < 2) { return -1; }
	if (a[11] == 2) { return 1; }
	unsigned value = u16(b), low = u16(a + 12), high = u16(a + 14);
	return value >= low && (!high || value <= high);
}
static struct kr_direction_score direction(const struct kr_profile_data *a,
	const struct kr_profile_data *b)
{
	struct kr_direction_score out = {0};
	double sum[9] = {0}; unsigned count[9] = {0};
	static const unsigned strict_q[] = {90,93,91,95};
	if (!a->strict[0]) {
		for (unsigned i = 0; i < 5; ++i) {
			if (!(a->strict[1] & (1u << i))) { continue; }
			int accepted;
			if (i == 4) { accepted = age(a->personal, b->personal); }
			else {
				unsigned wish = strict_q[i] - 65, actual = strict_q[i] - 65;
				accepted = state(a->wishes + 31, wish) || state(b->actual + 31, actual) ? -1 :
					(a->wishes[wish] & (1u << (b->actual[actual] - 1))) != 0;
			}
			if (accepted < 0) { out.unresolved |= 1u << i; }
			else if (!accepted) { out.excluded |= 1u << i; }
		}
	}
	unsigned tolerance = a->tolerance[1] - 1;
	for (unsigned q = 61; q <= 95; ++q) {
		bool trait = q <= 64;
		unsigned i = trait ? q - 61 : q - 65;
		unsigned ws = trait ? state(a->wish_traits + 4, i) : state(a->wishes + 31, i);
		if (ws) { continue; }
		unsigned wish = trait ? a->wish_traits[i] : a->wishes[i];
		double low = 0, high = 0, bottom = trait ? 0 : 1, top = trait ? 100 : 5;
		bool interval = trait || numeric(q - 35);
		if (interval) {
			if (trait) {
				static const unsigned lows[] = {0,0,30,60}, highs[] = {0,40,70,100};
				low = max(0, (double)lows[wish] - 10 * tolerance);
				high = min(100, (double)highs[wish] + 10 * tolerance);
			} else {
				low = q == 89 ? 1 : max(1, (double)wish - tolerance);
				high = min(5, wish + tolerance);
			}
			if (low == bottom && high == top) { continue; }
		}
		++out.informative;
		unsigned as = trait ? state(b->traits + 8, i) : state(b->actual + 31, i);
		if (as) { continue; }
		double fit;
		if (interval) {
			double actual = trait ? u16(b->traits + 2 * i) / 100.0 : b->actual[i];
			double distance = max(max(low - actual, actual - high), 0);
			fit = max(0, 1 - distance / max(low - bottom, top - high));
		} else if (q == 87) {
			unsigned other = b->actual[i] & 15u, wanted = wish & 15u;
			unsigned mine = a->actual[i] & 15u;
			if (!other || state(a->actual + 31, i) || !mine) { continue; }
			if (!(other & mine)) { fit = 0; }
			else if (!wanted) { continue; }
			else { fit = (other & wanted) ? 1 : 0; }
		} else {
			unsigned actual = q == 75 ? b->actual[i] : 1u << (b->actual[i] - 1);
			fit = (wish & actual) ? 1 : 0;
		}
		++out.observed; unsigned d = domain(q); sum[d] += fit; ++count[d];
	}
	double total = 0, weights = 0;
	unsigned priority = !a->priority[0] ? u16(a->priority + 1) : 0;
	for (unsigned i = 0; i < 9; ++i) {
		if (!count[i]) { continue; }
		unsigned weight = priority & (1u << i) ? 2 : 1;
		total += sum[i] / count[i] * weight; weights += weight;
	}
	out.has_score = weights != 0;
	if (out.has_score) { out.score = 100 * total / weights; }
	out.coverage = out.informative ? (double)out.observed / out.informative : 0;
	return out;
}

bool kr_match_score(const uint8_t *a, size_t an, const uint8_t *b, size_t bn,
	struct kr_pair_score *out)
{
	memset(out, 0, sizeof(*out)); struct kr_profile_data av, bv;
	if (!kr_profile_data_decode(a, an, &av) || !kr_profile_data_decode(b, bn, &bv)) { return false; }
	/* No invented default tolerance, priority or strictness for unresolved input. */
	if (av.tolerance[0] || bv.tolerance[0] || (av.priority[0] != 0 && av.priority[0] != 2) ||
		(bv.priority[0] != 0 && bv.priority[0] != 2) || (av.strict[0] != 0 && av.strict[0] != 2) ||
		(bv.strict[0] != 0 && bv.strict[0] != 2)) { return false; }
	out->ab = direction(&av, &bv); out->ba = direction(&bv, &av); out->calculated = true;
	out->has_harmonic = out->ab.has_score && out->ba.has_score;
	if (out->has_harmonic && out->ab.score + out->ba.score != 0) {
		out->harmonic = 2 * out->ab.score * out->ba.score / (out->ab.score + out->ba.score);
	}
	out->match = out->has_harmonic && out->ab.coverage >= .7 && out->ba.coverage >= .7 &&
		out->ab.score >= 50 && out->ba.score >= 50 && !out->ab.excluded && !out->ba.excluded &&
		!out->ab.unresolved && !out->ba.unresolved;
	return true;
}

int kr_match_eligibility(const uint8_t *a, size_t an, const uint8_t *b, size_t bn)
{
	struct kr_profile_data av, bv;
	if (!kr_profile_data_decode(a, an, &av) || !kr_profile_data_decode(b, bn, &bv)) { return -1; }
	if (!(av.options & bv.options & 1)) { return 0; }
	const uint8_t *p[] = {av.personal, bv.personal};
	int result = 1;
	for (unsigned i = 0; i < 2; ++i) {
		const uint8_t *own = p[i], *other = p[1 - i];
		if (state(own + 9, 1) || state(other + 9, 1) || state(own + 9, 2) ||
			state(own + 9, 4)) { result = -1; continue; }
		/* Current product: opposite gender only; ANY/multiple cannot bypass it. */
		if (own[2] == other[2] || own[3] != (1u << (other[2] - 1))) { return 0; }
		int accepted = age(own, other);
		if (!accepted) { return 0; }
		if (accepted < 0) { result = -1; }
		if (own[5]) {
			if (state(other + 9, 3)) { result = -1; }
			else if (!other[4]) { return 0; }
		}
	}
	return result;
}

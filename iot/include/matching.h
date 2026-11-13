#ifndef TINGLE_MATCHING_H
#define TINGLE_MATCHING_H

#include "profile.h"

/* Development port of the pinned engine100 reference, not a production release ID. */
#define KR_MATCH_POLICY UINT32_C(0x52504331)
#define KR_MATCH_RULE UINT32_C(0x10001101)
struct kr_direction_score {
	double score, coverage;
	unsigned informative, observed, excluded, unresolved;
	bool has_score;
};
struct kr_pair_score {
	struct kr_direction_score ab, ba;
	double harmonic;
	bool has_harmonic, calculated, match;
};
bool kr_match_score(const uint8_t *a, size_t an, const uint8_t *b, size_t bn,
	struct kr_pair_score *out);
/* 1 eligible, 0 excluded, -1 missing evidence. Separate from the PC score. */
int kr_match_eligibility(const uint8_t *a, size_t an, const uint8_t *b, size_t bn);

#endif

#include "peer.h"
#include "matching.h"
#include "keyring.h"
#include "monocypher.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void peer_fixture(struct kr_peer_material *, unsigned, const uint8_t[196], uint8_t[32]);
void peer_fixture_boot(struct kr_peer_material *, unsigned, const uint8_t[196], uint8_t[32], unsigned);
static struct kr_engine engines[10];
static struct kr_peer_runtime runtime[10];
static unsigned writes, feedbacks;
static int config(void *ctx, const struct kr_config *v) { (void)ctx; (void)v; ++writes; return 0; }
static int record(void *ctx, unsigned slot, const struct kr_record *v) { (void)ctx; (void)slot; (void)v; ++writes; return 0; }
static int suppression(void *ctx, unsigned slot, const struct kr_suppression *v) { (void)ctx; (void)slot; (void)v; return 0; }
static void random_bytes(void *ctx, uint8_t *out, size_t n)
{
	unsigned *state = ctx; for (size_t i = 0; i < n; ++i) { *state = *state * 1664525 + 1013904223; out[i] = (uint8_t)(*state >> 24); }
}
static void feedback(void *ctx, const uint8_t id[16]) { (void)ctx; (void)id; ++feedbacks; }
static void load(const char *path, uint8_t out[196])
{
	FILE *f = fopen(path, "rb"); assert(f); assert(fread(out, 1, 196, f) == 196); assert(fgetc(f) == EOF); assert(!fclose(f));
}
static bool contains(const uint8_t *a, size_t an, const uint8_t *b, size_t bn)
{
	for (size_t i = 0; i + bn <= an; ++i) { if (!memcmp(a + i, b, bn)) { return true; } } return false;
}
static void score(const char *a, const char *b)
{
	uint8_t av[196], bv[196]; load(a, av); load(b, bv);
	struct kr_pair_score p; bool ok = kr_match_score(av, 196, bv, 196, &p);
	printf("{\"calculated\":%s,\"match\":%s,\"ab\":", ok ? "true" : "false", p.match ? "true" : "false");
	if (p.ab.has_score) { printf("%.17g", p.ab.score); } else { printf("null"); }
	printf(",\"ba\":"); if (p.ba.has_score) { printf("%.17g", p.ba.score); } else { printf("null"); }
	printf(",\"coverageAb\":%.17g,\"coverageBa\":%.17g,\"harmonic\":", p.ab.coverage, p.ba.coverage);
	if (p.has_harmonic) { printf("%.17g", p.harmonic); } else { printf("null"); }
	printf(",\"excludedAb\":%u,\"excludedBa\":%u,\"unresolvedAb\":%u,\"unresolvedBa\":%u}\n",
		p.ab.excluded, p.ba.excluded, p.ab.unresolved, p.ba.unresolved);
}
int main(int argc, char **argv)
{
	if (argc == 4 && !strcmp(argv[1], "--score")) { score(argv[2], argv[3]); return 0; }
	assert(argc == 2); uint8_t payload[196]; load(argv[1], payload);
	struct kr_io io = {.config = config, .record = record, .suppression = suppression,
		.random = random_bytes, .feedback = feedback};
	static unsigned rng[10]; uint8_t server[32];
	for (unsigned i = 0; i < 10; ++i) {
		struct kr_peer_material m; peer_fixture(&m, i, payload, server);
		rng[i] = i + 1; io.ctx = &rng[i]; kr_init(&engines[i], m.device, io);
		assert(!kr_peer_start(&runtime[i], &m, server, &engines[i]));
		struct kr_peer_verified v; assert(kr_peer_certificate(m.certificate, m.public_key, server, 1793577901, &v));
		m.certificate[197] ^= 1; assert(!kr_peer_certificate(m.certificate, m.public_key, server, 1793577901, &v));
		m.certificate[197] ^= 1; m.certificate[261] ^= 1;
		assert(!kr_peer_certificate(m.certificate, m.public_key, server, 1793577901, &v));
	}
	uint8_t packet[512]; int sent; unsigned encrypted = 0, dropped = 0;
	for (unsigned turn = 0; turn < 800; ++turn) {
		for (unsigned a = 0; a < 10; ++a) {
			size_t n = kr_peer_advertisement(&runtime[a], &engines[a], packet, &sent); assert(n && n <= 512);
			assert(!contains(packet, n, runtime[a].own.payload, 196));
			if (packet[3] == 3) {
				++encrypted;
				/* Forged ciphertext, recipient and replay cannot mutate durable records. */
				for (unsigned b = 0; b < 10; ++b) {
					if (b == a || memcmp(packet + 36, runtime[b].own.public_key, 32)) { continue; }
					uint8_t bad[512]; memcpy(bad, packet, n); bad[n - 1] ^= 1; unsigned old = writes;
					assert(!kr_peer_receive(&runtime[b], &engines[b], bad, n, 1793577901)); assert(writes == old);
				}
			}
			/* Deterministic loss and duplicates; no queue dwell timer or score sorting. */
			if ((turn + a) % 7 == 0) { ++dropped; }
			else {
				for (unsigned b = 0; b < 10; ++b) {
					if (b == a || (packet[3] != 1 && memcmp(packet + 36, runtime[b].own.public_key, 32))) { continue; }
					(void)kr_peer_receive(&runtime[b], &engines[b], packet, n, 1793577901);
					unsigned old = writes; (void)kr_peer_receive(&runtime[b], &engines[b], packet, n, 1793577901); assert(writes == old);
				}
			}
			kr_sent(&engines[a], sent); kr_drain(&engines[a]);
		}
	}
	assert(encrypted && dropped);
	/* Reboot one board, preserve all durable rows, require fresh signed sync,
	 * and reject old session traffic. No record is acknowledged/deleted here. */
	uint64_t old_sequence = runtime[0].sequence;
	uint8_t old_public[32]; memcpy(old_public, runtime[0].own.public_key, 32);
	assert(kr_restored_valid(&engines[0]) && !engines[0].ready);
	struct kr_peer_material fresh; peer_fixture_boot(&fresh, 0, payload, server, 1);
	assert(!kr_peer_start(&runtime[0], &fresh, server, &engines[0]));
	assert(runtime[0].sequence == old_sequence && memcmp(old_public, runtime[0].own.public_key, 32));
	unsigned previous_feedback = feedbacks;
	for (unsigned turn = 0; turn < 160; ++turn) {
		for (unsigned a = 0; a < 10; ++a) {
			size_t n = kr_peer_advertisement(&runtime[a], &engines[a], packet, &sent);
			assert(n);
			for (unsigned b = 0; b < 10; ++b) {
				if (b != a && (packet[3] == 1 || !memcmp(packet + 36, runtime[b].own.public_key, 32))) {
					(void)kr_peer_receive(&runtime[b], &engines[b], packet, n, 1793577902);
				}
			}
			kr_sent(&engines[a], sent); kr_drain(&engines[a]);
		}
	}
	assert(feedbacks == previous_feedback);
	/* Discovery pressure cannot evict authenticated sessions. At inactivity,
	 * only keys/cache may be reclaimed; all 3 durable queues remain intact. */
	for (unsigned i = 0; i < 40; ++i) {
		memset(packet, 0, KR_PEER_HEADER); memcpy(packet, "PX\1\1", 4); memset(packet + 4, (int)(i + 100), 32);
		(void)kr_peer_receive(&runtime[0], &engines[0], packet, KR_PEER_HEADER, 1793577902);
	}
	unsigned protected_sessions = 0;
	for (unsigned i = 0; i < KR_PEERS; ++i) { protected_sessions += runtime[0].sessions[i].authenticated; }
	fprintf(stderr, "restarted peer auth=%u protected=%u confirmed=%u exchanged=%u\n", runtime[0].authenticated,
		protected_sessions, kr_count(&engines[0], KR_CONFIRMED), runtime[0].exchanged);
	assert(protected_sessions == 9 && kr_count(&engines[0], KR_CONFIRMED) == 5);
	/* Valid authenticated encryption but corrupt DATA belongs in review exactly
	 * once. In contrast, the earlier invalid-MAC traffic caused no flash writes. */
	struct kr_peer_session *sender = NULL;
	for (unsigned i = 0; i < KR_PEERS; ++i) {
		if (runtime[1].sessions[i].authenticated && !memcmp(runtime[1].sessions[i].public_key,
			runtime[0].own.public_key, 32)) { sender = &runtime[1].sessions[i]; }
	}
	assert(sender); uint8_t malformed[3] = {0,1,2}, nonce[24] = {0};
	memcpy(packet, "PX\1\3", 4); memcpy(packet + 4, runtime[1].own.public_key, 32);
	memcpy(packet + 36, runtime[0].own.public_key, 32);
	kr_put64(packet + 68, ++runtime[1].sequence); kr_put16(packet + 76, 3); kr_put64(nonce + 16, runtime[1].sequence);
	crypto_aead_lock(packet + KR_PEER_HEADER, packet + KR_PEER_HEADER + 3, sender->tx_key,
		nonce, packet, KR_PEER_HEADER, malformed, sizeof(malformed));
	assert(!kr_peer_receive(&runtime[0], &engines[0], packet, KR_PEER_HEADER + 19, 1793577902));
	assert(kr_count(&engines[0], KR_REVIEW) == 5); unsigned saved_writes = writes;
	assert(!kr_peer_receive(&runtime[0], &engines[0], packet, KR_PEER_HEADER + 19, 1793577902));
	assert(writes == saved_writes);
	for (unsigned i = 0; i < 10; ++i) {
		fprintf(stderr, "peer=%u auth=%u data=%u rejected=%u queues=%u/%u/%u\n", i,
			runtime[i].authenticated, runtime[i].exchanged, runtime[i].rejected,
			kr_count(&engines[i], KR_MATCH), kr_count(&engines[i], KR_CONFIRMED), kr_count(&engines[i], KR_REVIEW));
	}
	for (unsigned i = 0; i < 10; ++i) {
		assert(kr_count(&engines[i], KR_CONFIRMED) == 5);
		assert(kr_count(&engines[i], KR_REVIEW) == (i == 0 ? 5 : 4));
		assert(kr_count(&engines[i], KR_MATCH) == 0);
		assert(runtime[i].authenticated >= 9 && runtime[i].exchanged);
		unsigned old = feedbacks;
		assert(kr_restored_valid(&engines[i]) && !engines[i].ready);
		assert(!kr_peer_advertisement(&runtime[i], &engines[i], packet, &sent));
		kr_peer_stop(&runtime[i]); assert(!runtime[i].active); assert(feedbacks == old);
	}
	assert(feedbacks == 50);
	puts("PASS: 10 peers, server/device proof, encrypted advertisements, both-direction matching, loss/replay/tamper, 3 queues and restart hold");
	return 0;
}

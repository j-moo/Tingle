#ifndef TINGLE_PEER_H
#define TINGLE_PEER_H

#include "profile.h"

#define KR_PEERS 16
#define KR_PEER_CERT_BYTES 325
#define KR_PEER_WIRE_MAX 512
#define KR_PEER_HEADER 78
#define KR_PEER_IDLE_SECONDS 30 /* Session cache only, never a durable queue TTL. */
struct kr_peer_material {
	uint8_t secret[32], public_key[32], certificate[KR_PEER_CERT_BYTES];
	uint8_t payload[KR_PROFILE_STORAGE_BYTES], owner[16], owner_ref[16];
	uint32_t revision;
	uint32_t opportunity_seconds;
	uint64_t device;
	bool valid, policy_ready;
};
struct kr_peer_verified {
	uint64_t device;
	uint32_t revision;
	uint32_t opportunity_seconds;
	uint8_t hash[32], owner[16], owner_ref[16];
	uint64_t valid_until;
	uint64_t ownership_epoch;
	uint8_t scope[16];
	bool policy_ready;
};
struct kr_peer_session {
	uint8_t public_key[32], tx_key[32], rx_key[32], certificate_hash[32];
	struct kr_peer_verified remote;
	uint64_t received, last_seen;
	bool used, authenticated, data_next, profile_next;
};
struct kr_peer_runtime {
	struct kr_peer_material own;
	struct kr_peer_session sessions[KR_PEERS];
	uint8_t server_key[32];
	uint64_t sequence;
	unsigned cursor, control_cursor;
	uint32_t rejected, overflow, authenticated, exchanged;
	uint64_t now, evidence_peer;
	const struct kr_peer_verified *input_peer;
	void *application;
	bool (*allowed)(void *, const struct kr_peer_verified *, uint64_t);
	bool (*admit)(void *, const struct kr_peer_verified *);
	int (*cache_certificate)(void *, unsigned, const uint8_t *, const uint8_t *);
	bool active;
};
struct kr_identity; struct kr_settings; struct kr_settings_auth; struct kr_engine;
bool kr_peer_material_make(struct kr_peer_material *out, const struct kr_identity *id,
	const struct kr_settings *settings, const struct kr_settings_auth *auth, const uint8_t seed[32]);
bool kr_peer_certificate(const uint8_t cert[KR_PEER_CERT_BYTES], const uint8_t ephemeral[32],
	const uint8_t server_key[32], uint64_t now, struct kr_peer_verified *out);
void kr_peer_scope(const uint8_t cert[KR_PEER_CERT_BYTES], uint64_t *, uint8_t scope[16]);
bool kr_peer_grant(const uint8_t grant[261], const uint8_t server_key[32], uint64_t now);
bool kr_peer_grant_matches(const struct kr_identity *, const struct kr_settings *, const struct kr_settings_auth *, const uint8_t grant[261]);
/* Runtime must initially be zeroed. Stop wipes keys, preserving a public nonce counter. */
void kr_peer_stop(struct kr_peer_runtime *p);
int kr_peer_start(struct kr_peer_runtime *p, const struct kr_peer_material *own,
	const uint8_t server_key[32], struct kr_engine *engine);
/* Only authenticated AEAD content reaches the shared three-queue engine. */
bool kr_peer_receive(struct kr_peer_runtime *p, struct kr_engine *engine,
	const uint8_t *wire, size_t len, uint64_t now);
size_t kr_peer_advertisement(struct kr_peer_runtime *p, struct kr_engine *engine,
	uint8_t out[KR_PEER_WIRE_MAX], int *sent_slot);

#endif

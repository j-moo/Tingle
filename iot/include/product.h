#ifndef TINGLE_PRODUCT_H
#define TINGLE_PRODUCT_H
#include "registration.h"
#include "keyring.h"
#include "policy.h"

#define KR_EVIDENCE_BYTES 795
#define KR_PEER_CACHE_BYTES 357
#define KR_OWNER_READ 1
#define KR_OWNER_ACK 2
#define KR_OWNER_RELEASE 4
#define KR_OWNER_CONTROL 8
#define KR_ENCOUNTER_PROOF "tingle-encounter-v2"
#define KR_OWNER_PROOF "tingle-owner-session-v2"
#define KR_RECEIPT_PROOF "tingle-record-receipt-v2"
struct kr_owner_scope { uint64_t epoch; uint8_t tag[16]; };

struct kr_public_name {
	int64_t device_id, ownership_epoch;
	char owner_generation[37];
	char name[KR_PUBLIC_NAME_MAX + 1];
};

struct kr_product_io {
	void *ctx;
	int64_t (*uptime_ms)(void *);
	int (*random)(void *, uint8_t *, size_t);
	/* read/write exact independent NVS items; length=0 deletes with readback. */
	int (*blob)(void *, uint16_t, void *, size_t, bool);
	int (*space)(void *, int64_t *, int64_t *);
	void (*stop_feedback)(void *);
	void (*refresh_phone)(void *);
	uint32_t (*generation)(void *);
};
struct kr_product {
	struct kr_core *core;
	struct kr_engine *engine;
	struct kr_peer_runtime *peers;
	struct kr_product_io io;
	int (*record)(void *, unsigned, const struct kr_record *);
	int (*suppression)(void *, unsigned, const struct kr_suppression *);
	void *record_ctx;
	uint8_t server_key[32], settings[KR_PAYLOAD_MAX];
	struct kr_policy policy;
	struct kr_public_name public_name;
	char applied_hash[65], owner_nonce[44];
	char session_owner[37];
	int64_t session_epoch, session_device;
	int64_t applied_version, utc_base, utc_uptime, challenge_deadline, session_deadline;
	uint32_t challenge_connection, session_connection;
	uint32_t applied_generation;
	unsigned permissions;
	uint64_t seen[KR_SUPPRESSIONS];
	uint8_t accounts[KR_RECORDS + KR_SUPPRESSIONS][16];
	struct kr_owner_scope scopes[KR_RECORDS + KR_SUPPRESSIONS];
	uint32_t peer_revisions[KR_RECORDS + KR_SUPPRESSIONS];
	uint8_t peer_hashes[KR_RECORDS + KR_SUPPRESSIONS][16];
	bool duplicate_suppression[KR_SUPPRESSIONS];
	bool policy_loaded, clock_ready, application_attached, public_name_storage_error;
};
void kr_product_init(struct kr_product *, struct kr_core *, struct kr_engine *, struct kr_product_io, const uint8_t[32]);
bool kr_product_apply(struct kr_product *, struct kr_peer_runtime *, int64_t verified_time);
void kr_product_bind_peer(struct kr_product *, struct kr_peer_runtime *);
void kr_product_forget_owner(struct kr_product *);
const char *kr_product_public_name(const struct kr_product *);
uint64_t kr_product_now(const struct kr_product *);
void kr_product_tick(struct kr_product *);
int kr_product_record(struct kr_product *, unsigned, const struct kr_record *);
int kr_product_suppression(struct kr_product *, unsigned, const struct kr_suppression *);
bool kr_product_same_peer(void *, unsigned, uint64_t);
bool kr_product_candidate_ready(void *, unsigned);
uint64_t kr_product_error_key(void *, uint64_t, const uint8_t *, size_t);
bool kr_product_dispatch(struct kr_product *, const struct kr_json *, const char *, struct kr_status, char response[KR_RESPONSE_MAX]);
bool kr_product_applied(const struct kr_product *);
bool kr_product_ready(const struct kr_product *);
#endif

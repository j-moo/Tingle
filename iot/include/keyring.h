#ifndef TINGLE_KEYRING_H
#define TINGLE_KEYRING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Test protocol, not the approved production proof/payload contract. */
#define KR_VERSION 1
#define KR_DATA_MAX 1024
#define KR_WIRE_MAX 1200
#define KR_RECORDS 32
#define KR_SUPPRESSIONS 64
#define KR_MATCH_LIMIT 8
#define KR_CONFIRMED_LIMIT 12
#define KR_REVIEW_LIMIT 12
#define KR_PROFILE_BYTES (34 + KR_DATA_MAX)
#define KR_BINARY_FRAME_MAX 2500

enum kr_kind { KR_EMPTY, KR_MATCH, KR_CONFIRMED, KR_REVIEW };
enum kr_phase { KR_NEW, KR_WAIT, KR_OFFER, KR_PREPARED, KR_COMMIT,
	KR_DONE, KR_FINAL, KR_COMPLETE };
enum kr_reason { KR_REASON_NONE, KR_BAD_DATA, KR_ALGORITHM_ERROR,
	KR_ALGORITHM_REJECT, KR_VERSION_MISMATCH, KR_SETTINGS_CHANGED,
	KR_RADIO_OVERFLOW, KR_ACCOUNT_DUPLICATE, KR_PEER_OWNERSHIP_CHANGED, KR_PEER_SETTINGS_CHANGED };
enum kr_result { KR_OK, KR_INVALID, KR_UNAUTHORIZED, KR_STALE,
	KR_FULL, KR_STORAGE, KR_NOT_READY, KR_NOT_FOUND, KR_CONFLICT };
enum kr_decision { KR_ACCEPT, KR_REJECT, KR_CALC_ERROR };

struct kr_profile {
	uint32_t revision;
	uint32_t policy;
	uint32_t rule;
	uint32_t opportunity_sec;
	uint8_t snapshot[16];
	uint16_t data_len;
	uint8_t data[KR_DATA_MAX];
};

struct kr_config {
	uint32_t format;
	uint64_t device;
	uint8_t owner[16];
	uint8_t token[16];
	uint8_t registered;
	struct kr_profile profile;
};

struct kr_record {
	uint32_t format;
	uint32_t sequence;
	uint64_t peer;
	uint64_t occurred_at;
	uint64_t expires_at;
	uint8_t id[16];
	uint8_t kind;
	uint8_t phase;
	uint8_t reason;
	uint8_t feedback_claimed;
	struct kr_profile own;
	uint16_t peer_len;
	uint8_t peer_wire[KR_WIRE_MAX];
};

struct kr_suppression {
	uint64_t peer;
	uint8_t id[16];
	uint64_t occurred_at;
	uint64_t expires_at;
	uint32_t own_revision;
	uint32_t peer_revision;
	uint8_t phase;
};

struct kr_io {
	/* Return zero only after durable storage. An EMPTY slot is a deletion. */
	int (*config)(void *, const struct kr_config *);
	int (*record)(void *, unsigned, const struct kr_record *);
	int (*suppression)(void *, unsigned, const struct kr_suppression *);
	/* Reserve the next unused number durably BEFORE publishing a new row. */
	int (*sequence)(void *, uint32_t);
	/* Match a frozen record/suppression to the authenticated remote owner. */
	bool (*same_peer)(void *, unsigned, uint64_t);
	bool (*candidate_ready)(void *, unsigned);
	uint64_t (*error_key)(void *, uint64_t, const uint8_t *, size_t);
	void (*random)(void *, uint8_t *, size_t);
	void (*feedback)(void *, const struct kr_record *, const struct kr_profile *peer);
	enum kr_decision (*calculate)(void *, const struct kr_profile *,
		const struct kr_profile *);
	bool synthetic_only;
	void *ctx;
};

struct kr_engine {
	struct kr_config config;
	struct kr_record records[KR_RECORDS];
	struct kr_suppression suppressed[KR_SUPPRESSIONS];
	struct kr_io io;
	uint32_t next_sequence;
	uint8_t cursor_epoch[16];
	uint32_t overflow;
	/* RAM-only isolation; the original NVS items remain untouched. */
	uint32_t quarantined_records;
	uint32_t final_retry_records;
	uint64_t final_retry_suppressions;
	bool ready;
	bool storage_error;
};

uint16_t kr_get16(const uint8_t *p);
uint32_t kr_get32(const uint8_t *p);
uint64_t kr_get64(const uint8_t *p);
void kr_put16(uint8_t *p, uint16_t n);
void kr_put32(uint8_t *p, uint32_t n);
void kr_put64(uint8_t *p, uint64_t n);
uint32_t kr_crc32(const uint8_t *data, size_t len);
size_t kr_profile_encode(const struct kr_profile *p, uint8_t *out);
bool kr_profile_decode(struct kr_profile *p, const uint8_t *in, size_t len);
size_t kr_packet(uint8_t type, const uint8_t *body, size_t len, uint8_t *out);
bool kr_packet_valid(const uint8_t *in, size_t len);
size_t kr_record_encode(const struct kr_record *r, uint8_t *out);

void kr_init(struct kr_engine *e, uint64_t device, struct kr_io io);
bool kr_restored_valid(struct kr_engine *e);
bool kr_record_valid(const struct kr_record *r);
bool kr_quarantined(const struct kr_engine *e, unsigned slot);
unsigned kr_quarantine_count(const struct kr_engine *e);
enum kr_result kr_register(struct kr_engine *e, const uint8_t owner[16],
	const uint8_t token[16]);
bool kr_auth(const struct kr_engine *e, const uint8_t token[16]);
enum kr_result kr_sync(struct kr_engine *e, const struct kr_profile *p);
enum kr_result kr_receive(struct kr_engine *e, const uint8_t *wire,
	size_t len, uint64_t source, uint64_t now);
enum kr_result kr_radio_error(struct kr_engine *e, uint64_t source,
	const uint8_t *raw, size_t len, enum kr_reason reason, uint64_t now);
/* Run every runnable job immediately; radio-dependent phases never block others. */
void kr_drain(struct kr_engine *e);
enum kr_result kr_abandon_candidate(struct kr_engine *, unsigned, enum kr_reason);
bool kr_retired_reason(unsigned);
void kr_expire_candidates(struct kr_engine *e, uint64_t now);
size_t kr_advertisement(struct kr_engine *e, unsigned *cursor, uint8_t *out,
	int *sent_slot);
void kr_sent(struct kr_engine *e, int slot);
bool kr_sent_guarded(struct kr_engine *, int, uint32_t, const uint8_t[16]);
enum kr_result kr_ack(struct kr_engine *e, const uint8_t id[16]);
enum kr_result kr_release(struct kr_engine *e, const uint8_t id[16]);
unsigned kr_count(const struct kr_engine *e, enum kr_kind kind);
unsigned kr_pending(const struct kr_engine *);
bool kr_can_insert(const struct kr_engine *, enum kr_kind);
enum kr_decision kr_always_match(void *ctx, const struct kr_profile *a,
	const struct kr_profile *b);

#endif

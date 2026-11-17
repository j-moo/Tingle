#ifndef TINGLE_DIAGNOSTICS_H
#define TINGLE_DIAGNOSTICS_H
#include "keyring.h"
#include "peer.h"

/* Volatile, public metadata only. Never written to NVS or sent over BLE. */
#define KR_DIAG_LINE_BYTES 640
enum kr_diag_command { KR_DIAG_NONE, KR_DIAG_STATUS, KR_DIAG_RECORDS, KR_DIAG_INVALID };
enum kr_diag_effect { KR_DIAG_UNKNOWN_PEER, KR_DIAG_SAME_SCOPE, KR_DIAG_INPUT_CHANGED,
	KR_DIAG_OWNER_CHANGED, KR_DIAG_RETIRED, KR_DIAG_DUPLICATE_REF, KR_DIAG_QUARANTINED,
	KR_DIAG_UNKNOWN_SCOPE, KR_DIAG_ERROR_KEY };
enum kr_diag_gate {
	KR_DIAG_NO_OWNER = 1u << 0, KR_DIAG_RESET = 1u << 1, KR_DIAG_STORAGE = 1u << 2,
	KR_DIAG_RECOVERY = 1u << 3, KR_DIAG_NO_POLICY = 1u << 4, KR_DIAG_NOT_APPLIED = 1u << 5,
	KR_DIAG_NO_TIME = 1u << 6, KR_DIAG_INCOMPLETE = 1u << 7, KR_DIAG_DISABLED = 1u << 8,
	KR_DIAG_OFF = 1u << 9, KR_DIAG_EXPIRED = 1u << 10, KR_DIAG_PEER_INACTIVE = 1u << 11,
	KR_DIAG_PEER_UNREADY = 1u << 12, KR_DIAG_QUARANTINE = 1u << 13,
	KR_DIAG_ACCOUNT_LOCK = 1u << 14, KR_DIAG_ENGINE_UNREADY = 1u << 15,
};
struct kr_diag_row {
	uint64_t peer, epoch;
	uint32_t own_revision, peer_revision;
	uint8_t slot, kind, phase, reason, effect, flags;
};
#define KR_DIAG_LEDGER 1u
#define KR_DIAG_SCOPE_KNOWN 2u
#define KR_DIAG_ACCOUNT_KNOWN 4u
#define KR_DIAG_FEEDBACK_CLAIMED 8u
struct kr_diag_inputs {
	uint32_t sequence;
	/* Recomputed from frozen inputs, never presented as a new match. -1 = unavailable. */
	int16_t eligibility, score_ab, score_ba, coverage_ab, coverage_ba;
	uint8_t strict_ab, strict_ba, unresolved_ab, unresolved_ba;
};
struct kr_diag_peer {
	uint64_t peer, epoch, last_seen, valid_until;
	uint32_t revision;
	bool authenticated, recent, policy_ready, allowed;
};
struct kr_diag_snapshot {
	char serial[65];
	int64_t device_id, epoch, stored_version, applied_version, uptime_ms;
	uint64_t now, filter;
	uint32_t gates, quarantined, authenticated, exchanged, rejected, overflow;
	unsigned candidates, confirmed, review, suppressions, missing_accounts, row_count, peer_count;
	bool available, records, applied, time_ready, ready, can_insert;
	bool scan_enabled, exchange_enabled, lab_enabled;
	struct kr_diag_row rows[KR_RECORDS + KR_SUPPRESSIONS];
	struct kr_diag_inputs inputs[KR_RECORDS];
	struct kr_diag_peer peers[KR_PEERS];
};
_Static_assert(sizeof(struct kr_diag_snapshot) <= 4608, "USB diagnostics must not consume the live matching buffers");
struct kr_product;
/* Caller holds core_lock followed by owner_data_lock. Capture has no write callbacks. */
void kr_product_diag_capture(const struct kr_product *, bool records, uint64_t filter, struct kr_diag_snapshot *);
/* A peer filter is an ENROLL XIAO serial, not an environment-specific DB ID. */
enum kr_diag_command kr_diag_parse(const char *, uint64_t *filter);
bool kr_diag_format(const struct kr_diag_snapshot *, unsigned line, char out[KR_DIAG_LINE_BYTES]);
#endif

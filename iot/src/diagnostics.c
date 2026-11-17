#include "diagnostics.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

static int nibble(char c)
{
	if (c >= '0' && c <= '9') { return c - '0'; }
	if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
	return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
}
enum kr_diag_command kr_diag_parse(const char *line, uint64_t *filter)
{
	*filter = 0;
	if (!strcmp(line, "MATCH STATUS")) { return KR_DIAG_STATUS; }
	if (!strcmp(line, "MATCH RECORDS")) { return KR_DIAG_RECORDS; }
	static const char prefix[] = "MATCH RECORDS XIAO-";
	if (!strncmp(line, prefix, sizeof(prefix) - 1)) {
		const char *serial = line + sizeof(prefix) - 1;
		if (strlen(serial) != 16) { return KR_DIAG_INVALID; }
		uint64_t peer = 0;
		for (unsigned i = 0; i < 8; ++i) {
			int a = nibble(serial[i * 2]), b = nibble(serial[i * 2 + 1]);
			if (a < 0 || b < 0) { return KR_DIAG_INVALID; }
			peer |= (uint64_t)(a * 16 + b) << (8 * i);
		}
		peer &= INT64_MAX; /* Same hardware namespace as verified peer certificates. */
		if (!peer) { return KR_DIAG_INVALID; }
		*filter = peer; return KR_DIAG_RECORDS;
	}
	return !strncmp(line, "MATCH", 5) ? KR_DIAG_INVALID : KR_DIAG_NONE;
}
static const char *named(unsigned value, const char *const *names, size_t count)
{ return value < count ? names[value] : "UNKNOWN"; }
static void gates(uint32_t bits, char *out, size_t capacity)
{
	static const char *const names[] = {"NO_OWNER", "RESET_IN_PROGRESS", "STORAGE_ERROR", "SETTINGS_RECOVERY",
		"POLICY_NOT_LOADED", "SETTINGS_NOT_APPLIED", "TIME_NOT_READY", "POLICY_INCOMPLETE", "POLICY_DISABLED",
		"MATCHING_OFF", "POLICY_EXPIRED", "PEER_INACTIVE", "PEER_POLICY_NOT_READY", "QUARANTINE_UNRESOLVED",
		"ACCOUNT_METADATA_MISSING", "ENGINE_NOT_READY"};
	out[0] = 0;
	for (unsigned i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
		if (!(bits & (1u << i))) { continue; }
		size_t used = strlen(out);
		(void)snprintf(out + used, capacity - used, "%s%s", used ? "," : "", names[i]);
	}
	if (!bits) { (void)snprintf(out, capacity, "NONE"); }
}
bool kr_diag_format(const struct kr_diag_snapshot *s, unsigned line, char out[KR_DIAG_LINE_BYTES])
{
	static const char *const kinds[] = {"EMPTY", "CANDIDATE", "CONFIRMED", "REVIEW"};
	static const char *const phases[] = {"NEW", "WAIT", "OFFER", "PREPARED", "COMMIT", "DONE", "FINAL", "COMPLETE"};
	static const char *const reasons[] = {"NONE", "BAD_DATA", "ALGORITHM_ERROR", "ALGORITHM_REJECT", "VERSION_MISMATCH",
		"SETTINGS_CHANGED", "RADIO_OVERFLOW", "ACCOUNT_DUPLICATE", "PEER_OWNERSHIP_CHANGED", "PEER_SETTINGS_CHANGED"};
	static const char *const effects[] = {"UNKNOWN_NO_RECENT_PEER", "SAME_SCOPE_EXISTS", "FAILED_INPUT_CHANGED",
		"DIFFERENT_OWNER_SCOPE", "RETIRED", "DUPLICATE_REFERENCE_IGNORED", "QUARANTINED", "UNKNOWN_SCOPE", "ERROR_KEY_NOT_DEVICE"};
	out[0] = 0;
	if (!line) {
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH BEGIN format=1 mode=%s available=%u uptimeMs=%" PRId64 " utc=%" PRIu64 " filterPeerKey=%016" PRIx64,
			s->records ? "RECORDS" : "STATUS", s->available, s->uptime_ms, s->now, s->filter);
	} else if (!s->available) {
		if (line != 1) { return false; }
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH END unavailable=APPLICATION_NOT_ATTACHED");
	} else if (line == 1) {
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH STATE serial=%s deviceId=%" PRId64 " epoch=%" PRId64 " stored=%" PRId64 " appliedVersion=%" PRId64
			" applied=%u timeReady=%u matchingReady=%u", s->serial, s->device_id, s->epoch, s->stored_version,
			s->applied_version, s->applied, s->time_ready, s->ready);
	} else if (line == 2) {
		char text[400]; gates(s->gates, text, sizeof(text));
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH GATES reasons=%s", text);
	} else if (line == 3) {
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH QUEUES candidate=%u confirmed=%u review=%u suppression=%u quarantined=%08" PRIx32
			" missingAccountMetadata=%u canInsertCandidate=%u", s->candidates, s->confirmed, s->review, s->suppressions,
			s->quarantined, s->missing_accounts, s->can_insert);
	} else if (line == 4) {
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH RADIO scan=%u exchange=%u lab=%u authenticated=%" PRIu32 " exchanged=%" PRIu32
			" rejected=%" PRIu32 " overflow=%" PRIu32, s->scan_enabled, s->exchange_enabled, s->lab_enabled,
			s->authenticated, s->exchanged, s->rejected, s->overflow);
	} else if (line - 5 < s->peer_count) {
		const struct kr_diag_peer *p = &s->peers[line - 5];
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH PEER peerKey=%016" PRIx64 " epoch=%" PRIu64 " revision=%" PRIu32
			" authenticated=%u recent=%u policyReady=%u allowed=%s lastSeenUtc=%" PRIu64 " validUntil=%" PRIu64,
			p->peer, p->epoch, p->revision, p->authenticated, p->recent, p->policy_ready,
			p->authenticated && p->recent ? (p->allowed ? "YES" : "NO") : "UNKNOWN", p->last_seen, p->valid_until);
	} else if (line - 5 - s->peer_count < s->row_count) {
		const struct kr_diag_row *r = &s->rows[line - 5 - s->peer_count];
		bool ledger = (r->flags & KR_DIAG_LEDGER) != 0;
		const struct kr_diag_inputs unknown = {.eligibility = -1, .score_ab = -1, .score_ba = -1, .coverage_ab = -1, .coverage_ba = -1};
		const struct kr_diag_inputs *input = ledger ? &unknown : &s->inputs[r->slot];
		char epoch[24], sequence[16], revision[16];
		if (r->flags & KR_DIAG_SCOPE_KNOWN) { snprintf(epoch, sizeof(epoch), "%" PRIu64, r->epoch); }
		else { strcpy(epoch, "UNKNOWN"); }
		if (!ledger) { snprintf(sequence, sizeof(sequence), "%" PRIu32, input->sequence); }
		else { strcpy(sequence, "UNKNOWN"); }
		if (r->peer_revision) { snprintf(revision, sizeof(revision), "%" PRIu32, r->peer_revision); }
		else { strcpy(revision, "UNKNOWN"); }
		snprintf(out, KR_DIAG_LINE_BYTES,
			"MATCH ROW source=%s slot=%u peerKey=%016" PRIx64 " peerEpoch=%s kind=%s phase=%s reason=%s effect=%s"
			" sequence=%s ownRev=%" PRIu32 " peerRev=%s accountKnown=%u feedbackClaimed=%s"
			" frozenEligibility=%d scoreABx100=%d scoreBAx100=%d"
			" coverageABx10000=%d coverageBAx10000=%d strictAB=%x strictBA=%x unresolvedAB=%x unresolvedBA=%x",
			ledger ? "SUPPRESSION" : "RECORD", r->slot, r->peer, epoch,
			ledger ? (r->kind == KR_CONFIRMED ? "CONFIRMED_LEDGER" : "REVIEW_OR_LEGACY_LEDGER") :
				named(r->kind, kinds, sizeof(kinds) / sizeof(kinds[0])),
			named(r->phase, phases, sizeof(phases) / sizeof(phases[0])),
			ledger ? "NOT_RETAINED" : named(r->reason, reasons, sizeof(reasons) / sizeof(reasons[0])),
			named(r->effect, effects, sizeof(effects) / sizeof(effects[0])), sequence, r->own_revision,
			revision, (r->flags & KR_DIAG_ACCOUNT_KNOWN) != 0,
			ledger ? "UNKNOWN" : (r->flags & KR_DIAG_FEEDBACK_CLAIMED ? "1" : "0"),
			input->eligibility, input->score_ab, input->score_ba, input->coverage_ab, input->coverage_ba,
			input->strict_ab, input->strict_ba, input->unresolved_ab, input->unresolved_ba);
	} else if (line == 5 + s->peer_count + s->row_count) {
		snprintf(out, KR_DIAG_LINE_BYTES, "MATCH END rows=%u peers=%u result=SNAPSHOT_ONLY", s->row_count, s->peer_count);
	} else { return false; }
	return true;
}

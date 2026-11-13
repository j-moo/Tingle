#include "registration.h"
#include "profile.h"
#include "policy.h"
#include "monocypher-ed25519.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

void kr_hex(const uint8_t *data, size_t len, char *out)
{
	static const char hex[] = "0123456789abcdef";
	for (size_t i = 0; i < len; ++i) {
		out[2 * i] = hex[data[i] >> 4];
		out[2 * i + 1] = hex[data[i] & 15];
	}
	out[2 * len] = 0;
}

size_t kr_base64_encode(const uint8_t *src, size_t len, char *dst)
{
	size_t n = 0;
	uint32_t bits = 0;
	unsigned count = 0;
	for (size_t i = 0; i < len; ++i) {
		bits = (bits << 8) | src[i];
		count += 8;
		while (count >= 6) {
			count -= 6;
			dst[n++] = b64[(bits >> count) & 63];
		}
	}
	if (count) { dst[n++] = b64[(bits << (6 - count)) & 63]; }
	dst[n] = 0;
	return n;
}

int kr_base64_decode(const char *src, uint8_t *dst, size_t capacity)
{
	if (!src || strlen(src) % 4 == 1) { return -1; }
	size_t n = 0;
	uint32_t bits = 0;
	unsigned count = 0;
	for (; *src; ++src) {
		const char *digit = strchr(b64, *src);
		if (!digit) { return -1; }
		bits = (bits << 6) | (unsigned)(digit - b64);
		count += 6;
		if (count >= 8) {
			count -= 8;
			if (n == capacity) { return -1; }
			dst[n++] = (uint8_t)(bits >> count);
		}
	}
	if (count && (bits & ((1u << count) - 1u))) { return -1; }
	return (int)n;
}

bool kr_identifier(const char *text)
{
	if (!text || !*text || strlen(text) > 64) { return false; }
	for (; *text; ++text) {
		if (!((*text >= 'a' && *text <= 'z') || (*text >= 'A' && *text <= 'Z') ||
			(*text >= '0' && *text <= '9') || strchr("._-", *text))) { return false; }
	}
	return true;
}

bool kr_uuid(const char *text)
{
	if (!text || strlen(text) != 36) { return false; }
	for (size_t i = 0; i < 36; ++i) {
		if (i == 8 || i == 13 || i == 18 || i == 23) {
			if (text[i] != '-') { return false; }
		} else if (!strchr("0123456789abcdef", text[i])) { return false; }
	}
	return true;
}

bool kr_nonce(const char *text)
{
	uint8_t nonce[32];
	return text && strlen(text) == 43 && kr_base64_decode(text, nonce, sizeof(nonce)) == 32;
}

bool kr_positive(const char *text, int64_t *out)
{
	if (!text || *text < '1' || *text > '9') { return false; }
	uint64_t n = 0;
	for (; *text; ++text) {
		if (*text < '0' || *text > '9' || n > ((uint64_t)INT64_MAX - (*text - '0')) / 10) {
			return false;
		}
		n = n * 10 + (unsigned)(*text - '0');
	}
	*out = (int64_t)n;
	return true;
}

/* Server Instant.toString(): UTC, optional fractional seconds. Deliberately no
 * local timezone conversion. The transcript uses getEpochSecond(), not millis. */
bool kr_epoch(const char *iso, int64_t *out)
{
	if (!iso || strlen(iso) < 20) { return false; }
	int v[6] = {0};
	const int start[] = {0, 5, 8, 11, 14, 17};
	const int width[] = {4, 2, 2, 2, 2, 2};
	if (iso[4] != '-' || iso[7] != '-' || iso[10] != 'T' || iso[13] != ':' || iso[16] != ':') {
		return false;
	}
	for (int f = 0; f < 6; ++f) {
		for (int j = 0; j < width[f]; ++j) {
			char c = iso[start[f] + j];
			if (c < '0' || c > '9') { return false; }
			v[f] = v[f] * 10 + c - '0';
		}
	}
	const char *tail = iso + 19;
	if (*tail == '.') {
		const char *fraction = ++tail;
		while (*tail >= '0' && *tail <= '9') { ++tail; }
		if (tail == fraction || tail - fraction > 9) { return false; }
	}
	if (strcmp(tail, "Z") || v[0] < 1970 || v[1] < 1 || v[1] > 12 ||
		v[3] > 23 || v[4] > 59 || v[5] > 59) { return false; }
	const int month_days[] = {31,28,31,30,31,30,31,31,30,31,30,31};
	int leap = v[0] % 4 == 0 && (v[0] % 100 != 0 || v[0] % 400 == 0);
	if (v[2] < 1 || v[2] > month_days[v[1] - 1] + (v[1] == 2 && leap)) { return false; }
	int64_t days = 0;
	for (int year = 1970; year < v[0]; ++year) {
		days += 365 + (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
	}
	for (int month = 1; month < v[1]; ++month) {
		days += month_days[month - 1] + (month == 2 && leap);
	}
	*out = ((days + v[2] - 1) * 24 + v[3]) * 3600 + v[4] * 60 + v[5];
	return *out > 0;
}

static int sign_text(const struct kr_identity *id, const char *text, int size, char out[87])
{
	if (size < 0 || size >= 640 || !kr_identifier(id->key_id) || !kr_identifier(id->serial)) {
		return -1;
	}
	uint8_t signature[64];
	crypto_ed25519_sign(signature, id->secret, (const uint8_t *)text, (size_t)size);
	kr_base64_encode(signature, sizeof(signature), out);
	return 0;
}

int kr_registration_proof(const struct kr_identity *id, const char *challenge,
	const char *nonce, int64_t expires, char signature[87])
{
	if (!kr_uuid(challenge) || !kr_nonce(nonce) || expires <= 0) { return -1; }
	char transcript[640];
	int n = snprintf(transcript, sizeof(transcript),
		"TINGLE-KEYRING-REGISTER\n%s\n%s\n%s\n%s\n%s\n%" PRId64 "\n",
		KR_PROOF_VERSION, id->key_id, id->serial, challenge, nonce, expires);
	return sign_text(id, transcript, n, signature);
}

int kr_registration_proof_v2(const struct kr_identity *id, const char *challenge,
	const char *nonce, int64_t expires, int64_t ownership_epoch, char signature[87])
{
	if (!kr_uuid(challenge) || !kr_nonce(nonce) || expires <= 0 || ownership_epoch < 0) { return -1; }
	char text[640];
	int size = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-REGISTER\n%s\n%s\n%s\n%s\n%s\n%" PRId64 "\n%" PRId64 "\nUNOWNED\n",
		KR_PROOF_VERSION_2, id->key_id, id->serial, challenge, nonce, expires, ownership_epoch);
	return sign_text(id, text, size, signature);
}

bool kr_settings_valid(const struct kr_settings *s)
{
	/* Check termination before calling string routines on persisted bytes. */
	if (s->magic != KR_RECORD_MAGIC || !s->payload_size || s->payload_size > KR_PAYLOAD_MAX ||
		s->device_id <= 0 || s->version <= 0 || s->owner_generation[36] || s->sync_run[36] ||
		s->nonce[43] || s->hash[64] || s->format[64]) { return false; }
	if (!kr_uuid(s->owner_generation) || !kr_uuid(s->sync_run) || !kr_nonce(s->nonce) ||
		!kr_identifier(s->format) || strlen(s->hash) != 64) { return false; }
	struct kr_profile_data profile;
	if (!strcmp(s->format, KR_PROFILE_STORAGE_FORMAT) &&
		!kr_profile_data_decode(s->payload, s->payload_size, &profile)) { return false; }
	struct kr_policy policy;
	if (kr_policy_format(s->format) && (!kr_policy_decode(s->payload, s->payload_size, &policy) ||
		s->payload[3] != (!strcmp(s->format, KR_POLICY_FORMAT) ? '3' : '2'))) { return false; }
	uint8_t digest[32];
	char hex[65];
	kr_hash(s->payload, s->payload_size, digest);
	kr_hex(digest, sizeof(digest), hex);
	return !strcmp(hex, s->hash);
}

const char *kr_store(struct kr_core *core, const struct kr_settings *next,
	const struct kr_settings_auth *auth, char signature[87])
{
	core->peer_after_stored.verified_time = 0;
	core->peer_after_stored.settings_apply_attempt = 0;
	core->stop_peer = false;
	if (core->storage_error) { return "STORAGE_ERROR"; }
	if (core->reset.phase == KR_RESETTING) { return "RESET_IN_PROGRESS"; }
	if ((!strcmp(next->format, KR_PROFILE_STORAGE_FORMAT) || kr_policy_format(next->format)) && !core->profile_transport_protected) {
		return "SECURE_TRANSPORT_REQUIRED";
	}
	bool version2 = auth && auth->version2;
	if (core->reset.ownership_epoch && !version2) { return "PROTOCOL_UPGRADE_REQUIRED"; }
	if (version2 && auth->ownership_epoch != core->reset.ownership_epoch) {
		return "OWNERSHIP_EPOCH_MISMATCH";
	}
	if (!kr_settings_valid(next)) { return "INVALID_SETTINGS"; }
	if (core->reset.phase == KR_RESET_DONE &&
		!strcmp(next->owner_generation, core->reset.owner_generation)) { return "OWNER_MISMATCH"; }
	const struct kr_settings *old = &core->current;
	if (core->settings_recovery_required && next->version <= old->version &&
		!(core->settings_owner_only && next->version == old->version &&
		!strcmp(next->sync_run, old->sync_run) && !strcmp(next->nonce, old->nonce))) {
		return "SETTINGS_RECOVERY_REQUIRED";
	}
	if (old->magic) {
		if (old->device_id != next->device_id || strcmp(old->owner_generation, next->owner_generation)) {
			return "OWNER_MISMATCH";
		}
		if (next->version < old->version) { return "STALE_SETTINGS"; }
		if (next->version == old->version && (strcmp(old->hash, next->hash) ||
			strcmp(old->format, next->format))) { return "VERSION_CONFLICT"; }
	}
	if (!core->platform.authorize) { return "POLICY_UNRESOLVED"; }
	struct kr_peer_plan plan = {0};
	const char *error = core->platform.authorize(core->platform.ctx, next, auth, &plan);
	if (error) { return error; }
	if (plan.verified_time < 0 || plan.length > KR_DISCOVERY_MAX_BYTES ||
		(plan.length && (plan.verified_time == 0 || plan.length < KR_DISCOVERY_UUID_BYTES))) {
		return "INVALID_SETTINGS";
	}
	/* Stop advertising the old state even if the new setting's reply is lost. */
	core->stop_peer = next->version != old->version || (plan.verified_time && !plan.length);
	core->settings_apply_state = KR_SETTINGS_APPLY_NONE;
	if (memcmp(old, next, sizeof(*next)) || core->settings_owner_only) {
		if (!core->platform.save || core->platform.save(core->platform.ctx, next)) {
			/* An uncertain write may have committed. Stop serving mutable requests
			 * until a reboot/readback resolves the authoritative state. */
			core->storage_error = true;
			core->settings_recovery_required = true;
			core->stop_peer = true;
			return "STORAGE_ERROR";
		}
		core->current = *next;
		core->settings_recovery_required = false;
		core->settings_owner_only = false;
	}
	char transcript[640];
	int n = snprintf(transcript, sizeof(transcript),
		"TINGLE-KEYRING-STORED\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n%" PRId64 "\n%s\nSTORED\n",
		version2 ? KR_PROOF_VERSION_2 : KR_PROOF_VERSION,
		core->identity.key_id, core->identity.serial, next->device_id,
		next->owner_generation, next->sync_run, next->nonce, next->version, next->hash);
	if (version2 && n >= 0 && n < (int)sizeof(transcript)) {
		n += snprintf(transcript + n, sizeof(transcript) - (size_t)n,
			"%" PRId64 "\n", core->reset.ownership_epoch);
	}
	if (sign_text(&core->identity, transcript, n, signature)) { return "PROOF_ERROR"; }
	if (version2 && plan.verified_time > 0 && !strcmp(next->format, KR_POLICY_FORMAT)) {
		/* Even a byte-identical resubmission can stage a new radio plan. */
		if (!++core->settings_apply_attempt) { ++core->settings_apply_attempt; }
		core->settings_apply_epoch = auth->ownership_epoch;
		core->settings_apply_state = KR_SETTINGS_APPLY_PENDING;
		plan.settings_apply_attempt = core->settings_apply_attempt;
	}
	core->peer_after_stored = plan;
	return NULL;
}

bool kr_peer_after_response(struct kr_core *core, bool sent, struct kr_peer_plan *out)
{
	/* Full settings also publish an OFF/blocked plan so applied/time state
	 * can advance without starting a radio. Legacy discovery semantics stay. */
	bool start = sent && core->peer_after_stored.verified_time > 0 &&
		(core->peer_after_stored.length || kr_policy_format(core->current.format));
	if (start) {
		*out = core->peer_after_stored;
	} else {
		kr_settings_apply_finished(core, &core->peer_after_stored, false);
	}
	core->peer_after_stored.verified_time = 0;
	core->peer_after_stored.settings_apply_attempt = 0;
	return start;
}

static bool settings_apply_bound(const struct kr_core *core)
{
	return core->settings_apply_state != KR_SETTINGS_APPLY_NONE && !core->storage_error && !core->settings_recovery_required &&
		core->current.magic == KR_RECORD_MAGIC && !strcmp(core->current.format, KR_POLICY_FORMAT) &&
		core->reset.phase != KR_RESETTING && core->settings_apply_epoch == core->reset.ownership_epoch;
}

bool kr_settings_apply_current(const struct kr_core *core, const struct kr_peer_plan *plan)
{
	return plan->settings_apply_attempt && plan->settings_apply_attempt == core->settings_apply_attempt &&
		settings_apply_bound(core);
}

void kr_settings_apply_finished(struct kr_core *core, const struct kr_peer_plan *plan, bool success)
{
	if (kr_settings_apply_current(core, plan)) {
		core->settings_apply_state = success ? KR_SETTINGS_APPLIED : KR_SETTINGS_APPLY_FAILED;
	}
}

const char *kr_settings_applied_proof(const struct kr_core *core, const char *run, char signature[87])
{
	if (!core->profile_transport_protected) { return "SECURE_TRANSPORT_REQUIRED"; }
	const struct kr_settings *s = &core->current;
	if (!kr_uuid(run) || !settings_apply_bound(core) || strcmp(run, s->sync_run)) {
		return "SETTINGS_RUN_MISMATCH";
	}
	if (core->settings_apply_state == KR_SETTINGS_APPLY_PENDING) { return "SETTINGS_APPLY_PENDING"; }
	if (core->settings_apply_state != KR_SETTINGS_APPLIED) { return "SETTINGS_APPLY_FAILED"; }
	char transcript[640];
	int n = snprintf(transcript, sizeof(transcript),
		"TINGLE-KEYRING-APPLIED\n%s\n%s\n%s\n%" PRId64 "\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64 "\n%s\nAPPLIED\n",
		KR_SETTINGS_APPLIED_PROOF_VERSION, core->identity.key_id, core->identity.serial,
		s->device_id, s->owner_generation, core->settings_apply_epoch, s->sync_run, s->nonce, s->version, s->hash);
	return sign_text(&core->identity, transcript, n, signature) ? "PROOF_ERROR" : NULL;
}

#include "product.h"
#include "battery.h"
#include "storage.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* Access is serialized with encounter processing by the adapter owner lock. */
static uint8_t evidence[KR_EVIDENCE_BYTES];
static uint8_t export_bytes[KR_PAYLOAD_MAX];
static void load_accounts(struct kr_product *p);
static bool has_ref(const uint8_t ref[16])
{ uint8_t bits = 0; for (unsigned i = 0; i < 16; ++i) { bits |= ref[i]; } return bits != 0; }
static void remember_peer(struct kr_product *p, unsigned i, const uint8_t *cert)
{
	memcpy(p->accounts[i], cert + 93, 16); kr_peer_scope(cert, &p->scopes[i].epoch, p->scopes[i].tag);
	p->peer_revisions[i] = kr_get32(cert + 17); memcpy(p->peer_hashes[i], cert + 133, 16);
}
static unsigned missing_accounts(const struct kr_product *p)
{
	unsigned missing = 0;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &p->engine->records[i];
		if (r->kind && !kr_retired_reason(r->reason) && !(r->peer >> 63) &&
			(!has_ref(p->accounts[i]) || !has_ref(p->scopes[i].tag))) { ++missing; }
	}
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		const struct kr_suppression *s = &p->engine->suppressed[i];
		if (s->peer && !(s->peer >> 63) && (!has_ref(p->accounts[KR_RECORDS + i]) || !has_ref(p->scopes[KR_RECORDS + i].tag) ||
			(!s->peer_revision && !p->duplicate_suppression[i] && !p->peer_revisions[KR_RECORDS + i]))) { ++missing; }
	}
	return missing;
}

static int64_t uptime(const struct kr_product *p) { return p->io.uptime_ms(p->io.ctx); }
uint64_t kr_product_now(const struct kr_product *p)
{
	if (!p->clock_ready || uptime(p) < p->utc_uptime) { return 0; }
	return (uint64_t)p->utc_base + (uint64_t)(uptime(p) - p->utc_uptime) / 1000;
}
static void set_time(struct kr_product *p, int64_t utc)
{
	uint64_t old = kr_product_now(p);
	/* Never move the established trusted clock backwards to extend deadlines. */
	p->utc_base = old > (uint64_t)utc ? (int64_t)old : utc;
	p->utc_uptime = uptime(p); p->clock_ready = utc > 0;
}
static void uuid_text(const uint8_t *b, char out[37])
{
	unsigned j = 0; for (unsigned i = 0; i < 16; ++i) {
		if (i == 4 || i == 6 || i == 8 || i == 10) { out[j++] = '-'; }
		kr_hex(b + i, 1, out + j); j += 2;
	}
}
static void error(char *out, const char *request, const char *code)
{
	snprintf(out, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"ERROR\",\"requestId\":%s,\"code\":\"%s\"}\n", request, code);
}
static bool text(const struct kr_json *j, const char *key, const char *value)
{
	const char *s = kr_json_string(j, key); return s && !strcmp(s, value);
}
static bool authorized(const struct kr_product *p, unsigned permission)
{
	return p->core->profile_transport_protected && p->session_deadline > uptime(p) &&
		p->session_connection == p->core->connection_epoch && (p->permissions & permission) == permission &&
		p->core->current.magic && p->session_device == p->core->current.device_id &&
		p->session_epoch == p->core->reset.ownership_epoch && !strcmp(p->session_owner, p->core->current.owner_generation);
}

static const char *public_name_error(const char *name)
{
	if (!name || !*name) { return "INVALID_PUBLIC_NAME"; }
	size_t length = strlen(name);
	if (length > KR_PUBLIC_NAME_MAX) { return "PUBLIC_NAME_TOO_LONG"; }
	if (strspn(name, " ") == length) { return "INVALID_PUBLIC_NAME"; }
	const uint8_t *bytes = (const uint8_t *)name;
	for (size_t i = 0; i < length; ++i) {
		if (bytes[i] < 0x20 || bytes[i] == 0x7f ||
			(bytes[i] == 0xc2 && bytes[i + 1] >= 0x80 && bytes[i + 1] <= 0x9f)) {
			return "INVALID_PUBLIC_NAME";
		}
	}
	return NULL;
}

const char *kr_product_public_name(const struct kr_product *p)
{
	const struct kr_public_name *saved = &p->public_name;
	const struct kr_settings *owner = &p->core->current;
	if (!owner->magic || p->core->reset.phase == KR_RESETTING || p->core->storage_error ||
		p->engine->storage_error || saved->device_id != owner->device_id ||
		saved->ownership_epoch != p->core->reset.ownership_epoch ||
		memcmp(saved->owner_generation, owner->owner_generation, sizeof(saved->owner_generation))) {
		return "";
	}
	return saved->name;
}

static const char *set_public_name(struct kr_product *p, const char *name)
{
	struct kr_public_name next;
	memset(&next, 0, sizeof(next));
	if (name) {
		next.device_id = p->core->current.device_id;
		next.ownership_epoch = p->core->reset.ownership_epoch;
		memcpy(next.owner_generation, p->core->current.owner_generation, sizeof(next.owner_generation));
		strcpy(next.name, name);
	}
	int result = p->io.blob(p->io.ctx, KR_PUBLIC_NAME_STORAGE_KEY, &next, name ? sizeof(next) : 0, true);
	if (result) { memset(&next, 0, sizeof(next)); }
	p->public_name = next;
	p->public_name_storage_error = result != 0;
	if (p->io.refresh_phone) { p->io.refresh_phone(p->io.ctx); }
	return result ? "STORAGE_ERROR" : NULL;
}

static void public_name_command(struct kr_product *p, const struct kr_json *j, const char *type,
	const char *request, char response[KR_RESPONSE_MAX])
{
	const char *failure = NULL;
	if (!p->core->profile_transport_protected) { failure = "SECURE_TRANSPORT_REQUIRED"; }
	else if (!p->core->current.magic || p->core->reset.phase == KR_RESETTING) { failure = "OWNER_NOT_BOUND"; }
	else if (!authorized(p, KR_OWNER_CONTROL)) { failure = "OWNER_SESSION_REQUIRED"; }
	else if (p->core->storage_error || p->engine->storage_error) { failure = "STORAGE_ERROR"; }
	else if (!strcmp(type, "SET_PUBLIC_NAME")) {
		const char *name = kr_json_string(j, "name");
		failure = public_name_error(name);
		if (!failure) { failure = set_public_name(p, name); }
	} else if (!strcmp(type, "CLEAR_PUBLIC_NAME")) {
		failure = set_public_name(p, NULL);
	} else if (p->public_name_storage_error) {
		failure = "STORAGE_ERROR";
	}
	if (failure) { error(response, request, failure); return; }

	const char *name = kr_product_public_name(p);
	char quoted[KR_PUBLIC_NAME_MAX * 2 + 10] = "null";
	if (*name && !kr_json_quote(name, quoted, sizeof(quoted))) {
		error(response, request, "STORAGE_ERROR");
		return;
	}
	snprintf(response, KR_RESPONSE_MAX,
		"{\"protocolVersion\":1,\"type\":\"PUBLIC_NAME_RESULT\",\"requestId\":%s,\"enabled\":%s,\"name\":%s}\n",
		request, *name ? "true" : "false", quoted);
}

void kr_product_init(struct kr_product *p, struct kr_core *core, struct kr_engine *engine,
	struct kr_product_io io, const uint8_t key[32])
{
	memset(p, 0, sizeof(*p)); p->core = core; p->engine = engine; p->io = io;
	p->record = engine->io.record; p->record_ctx = engine->io.ctx; memcpy(p->server_key, key, 32);
	p->suppression = engine->io.suppression;
	if (!has_ref(engine->cursor_epoch) && io.random(io.ctx, engine->cursor_epoch, 16)) { engine->storage_error = true; }
	load_accounts(p);
	int result = io.blob(io.ctx, KR_PUBLIC_NAME_STORAGE_KEY, &p->public_name, sizeof(p->public_name), false);
	if (!result && (!memchr(p->public_name.name, 0, sizeof(p->public_name.name)) || public_name_error(p->public_name.name))) {
		result = -EILSEQ;
	}
	if (result) {
		memset(&p->public_name, 0, sizeof(p->public_name));
		p->public_name_storage_error = result != -ENOENT;
	}
	p->application_attached = true;
}
void kr_product_forget_owner(struct kr_product *p)
{
	crypto_wipe(p->settings, sizeof(p->settings)); memset(&p->policy, 0, sizeof(p->policy));
	memset(&p->public_name, 0, sizeof(p->public_name));
	p->public_name_storage_error = false;
	crypto_wipe(evidence, sizeof(evidence)); crypto_wipe(export_bytes, sizeof(export_bytes));
	memset(p->seen, 0, sizeof(p->seen)); memset(p->owner_nonce, 0, sizeof(p->owner_nonce));
	memset(p->accounts, 0, sizeof(p->accounts));
	memset(p->scopes, 0, sizeof(p->scopes));
	memset(p->peer_revisions, 0, sizeof(p->peer_revisions)); memset(p->peer_hashes, 0, sizeof(p->peer_hashes));
	memset(p->duplicate_suppression, 0, sizeof(p->duplicate_suppression));
	p->policy_loaded = p->clock_ready = false; p->session_deadline = p->challenge_deadline = 0;
	p->applied_hash[0] = 0; p->applied_version = 0; p->permissions = 0;
}
bool kr_product_apply(struct kr_product *p, struct kr_peer_runtime *peers, int64_t verified_time)
{
	p->peers = peers; p->policy_loaded = false;
	const struct kr_settings *s = &p->core->current;
	if (!s->magic || p->core->storage_error || p->core->settings_recovery_required ||
		p->core->reset.phase == KR_RESETTING || !kr_policy_format(s->format)) { return false; }
	memcpy(p->settings, s->payload, s->payload_size);
	if (!kr_policy_decode(p->settings, s->payload_size, &p->policy) ||
		verified_time <= 0 || !kr_peer_grant(p->policy.grant, p->server_key, (uint64_t)verified_time)) { return false; }
	set_time(p, verified_time);
	p->policy_loaded = true; p->applied_version = s->version; strcpy(p->applied_hash, s->hash);
	p->applied_generation = p->io.generation ? p->io.generation(p->io.ctx) : 0;
	return true;
}
bool kr_product_applied(const struct kr_product *p)
{
	return !p->core->settings_recovery_required && p->policy_loaded && (!p->io.generation || p->applied_generation == p->io.generation(p->io.ctx)) &&
		!p->engine->storage_error && p->policy.complete &&
		(!(p->policy.profile[195] & 1) || !p->policy.enabled ||
			(p->engine->config.profile.revision == (uint64_t)p->applied_version &&
			!memcmp(p->engine->config.profile.snapshot, p->policy.grant + 133, 16)));
}
static bool matching_conditions_ready(const struct kr_product *p)
{
	uint64_t now = kr_product_now(p);
	return kr_product_applied(p) && p->policy.complete && p->policy.enabled && now && now < p->policy.valid_until &&
		!p->engine->storage_error && (p->policy.profile[195] & 1) &&
		p->peers && p->peers->active && p->peers->own.policy_ready;
}
bool kr_product_ready(const struct kr_product *p)
{
	return matching_conditions_ready(p) && !missing_accounts(p) && p->engine->ready;
}
static bool scope_equal(const struct kr_owner_scope *scope, const struct kr_peer_verified *remote)
{ return scope->epoch == remote->ownership_epoch && !memcmp(scope->tag, remote->scope, 16); }
static bool failed_input_changed(const struct kr_product *p, unsigned i, const struct kr_peer_verified *remote)
{
	bool failed; uint32_t own_revision;
	if (i < KR_RECORDS) {
		failed = p->engine->records[i].kind == KR_REVIEW; own_revision = p->engine->records[i].own.revision;
	} else {
		const struct kr_suppression *s = &p->engine->suppressed[i - KR_RECORDS]; failed = s->peer && !s->peer_revision; own_revision = s->own_revision;
	}
	return failed && (own_revision != p->engine->config.profile.revision || (remote &&
		(!scope_equal(&p->scopes[i], remote) || p->peer_revisions[i] != remote->revision || memcmp(p->peer_hashes[i], remote->hash, 16))));
}
bool kr_product_same_peer(void *ctx, unsigned index, uint64_t peer)
{
	struct kr_product *p = ctx;
	if (peer >> 63 || !p->policy_loaded) { return true; } /* Synthetic lab namespace. */
	if (index >= KR_RECORDS + KR_SUPPRESSIONS || !p->peers || !p->peers->input_peer || p->peers->input_peer->device != peer) { return false; }
	if (index < KR_RECORDS && kr_retired_reason(p->engine->records[index].reason)) { return false; }
	if (index >= KR_RECORDS && p->duplicate_suppression[index - KR_RECORDS]) { return false; }
	if (failed_input_changed(p, index, p->peers->input_peer)) { return false; }
	return scope_equal(&p->scopes[index], p->peers->input_peer);
}
bool kr_product_candidate_ready(void *ctx, unsigned index)
{
	struct kr_product *p = ctx;
	if (!p->policy_loaded) { return true; }
	if (index >= KR_RECORDS || !p->peers || !kr_product_ready(p)) { return false; }
	const struct kr_record *r = &p->engine->records[index]; uint64_t now = kr_product_now(p);
	for (unsigned i = 0; i < KR_PEERS; ++i) {
		const struct kr_peer_session *s = &p->peers->sessions[i];
		if (s->authenticated && s->remote.device == r->peer && scope_equal(&p->scopes[index], &s->remote) &&
			s->remote.valid_until > now && s->last_seen <= now && now - s->last_seen <= p->policy.presence_seconds &&
			kr_policy_allows(&p->policy, s->remote.owner_ref, now)) { return true; }
	}
	return false;
}
uint64_t kr_product_error_key(void *ctx, uint64_t peer, const uint8_t *raw, size_t len)
{
	struct kr_product *p = ctx; uint8_t digest[8], source[8], empty[16] = {0}; crypto_blake2b_ctx hash;
	kr_put64(source, peer); crypto_blake2b_init(&hash, sizeof(digest));
	static const uint8_t domain[] = "TINGLE-REVIEW-OWNER-V1";
	crypto_blake2b_update(&hash, domain, sizeof(domain) - 1); crypto_blake2b_update(&hash, source, sizeof(source));
	crypto_blake2b_update(&hash, p->peers && p->peers->input_peer ? p->peers->input_peer->scope : empty, 16);
	crypto_blake2b_update(&hash, raw, len); crypto_blake2b_final(&hash, digest); return kr_get64(digest) | (1ULL << 63);
}
static bool allow_peer(void *ctx, const struct kr_peer_verified *remote, uint64_t now)
{
	struct kr_product *p = ctx;
	if (!remote->policy_ready || remote->valid_until <= now || !kr_product_ready(p) ||
		!kr_policy_allows(&p->policy, remote->owner_ref, now)) { return false; }
	for (unsigned i = 0; i < KR_RECORDS + KR_SUPPRESSIONS; ++i) {
		if (i < KR_RECORDS && (p->engine->records[i].kind == KR_MATCH || kr_retired_reason(p->engine->records[i].reason))) { continue; }
		if (i >= KR_RECORDS && p->duplicate_suppression[i - KR_RECORDS]) { continue; }
		if (failed_input_changed(p, i, remote)) { continue; }
		uint64_t peer = i < KR_RECORDS ? (p->engine->records[i].kind ? p->engine->records[i].peer : 0) : p->engine->suppressed[i - KR_RECORDS].peer;
		if (peer && !(peer >> 63) && !memcmp(p->accounts[i], remote->owner_ref, 16) &&
			(peer != remote->device || !scope_equal(&p->scopes[i], remote))) { return false; }
	}
	return true;
}
static bool admit_peer(void *ctx, const struct kr_peer_verified *remote)
{
	struct kr_product *p = ctx;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &p->engine->records[i];
		if (!r->kind || r->peer == remote->device || kr_retired_reason(r->reason) || failed_input_changed(p, i, remote) || memcmp(p->accounts[i], remote->owner_ref, 16)) { continue; }
		if (r->kind != KR_MATCH || r->peer < remote->device) { return false; }
		/* Resolve competing unconfirmed device choices deterministically.
		 * Keep superseded evidence in review; never roll back a confirmation. */
		if (kr_abandon_candidate(p->engine, i, KR_ACCOUNT_DUPLICATE) != KR_OK) { return false; }
	}
	return true;
}
static int cache_peer(void *ctx, unsigned slot, const uint8_t *cert, const uint8_t *public_key)
{
	struct kr_product *p = ctx; uint8_t bytes[KR_PEER_CACHE_BYTES];
	uint64_t epoch; uint8_t tag[16]; kr_peer_scope(cert, &epoch, tag);
	uint64_t hardware = kr_get64(cert + 1) & INT64_MAX;
	/* Never replace an already observed ownership generation with an old one.
	 * Frozen evidence and the bounded authenticated cache survive reboot. */
	for (unsigned i = 0; i < KR_RECORDS + KR_SUPPRESSIONS; ++i) {
		uint64_t peer = i < KR_RECORDS ? (p->engine->records[i].kind ? p->engine->records[i].peer : 0) : p->engine->suppressed[i - KR_RECORDS].peer;
		if (peer != hardware || !has_ref(p->scopes[i].tag)) { continue; }
		if (epoch < p->scopes[i].epoch || (epoch == p->scopes[i].epoch && memcmp(tag, p->scopes[i].tag, 16))) { return -EPERM; }
	}
	for (unsigned i = 0; i < KR_PEERS; ++i) {
		int cached = p->io.blob(p->io.ctx, (uint16_t)(500 + i), bytes, sizeof(bytes), false);
		if (cached == -ENOENT) { continue; }
		if (cached) { p->engine->storage_error = true; p->engine->ready = false; return -EIO; }
		if ((bytes[0] != 3 && bytes[0] != 4) ||
			(kr_get64(bytes + 1) & INT64_MAX) != hardware) { continue; }
		uint64_t previous; uint8_t previous_tag[16]; kr_peer_scope(bytes, &previous, previous_tag);
		if (epoch < previous || (epoch == previous && memcmp(tag, previous_tag, 16))) { return -EPERM; }
	}
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &p->engine->records[i]; if (r->kind != KR_MATCH || r->peer != hardware || r->reason) { continue; }
		bool changed_owner = epoch != p->scopes[i].epoch || memcmp(tag, p->scopes[i].tag, 16);
		struct kr_profile old_profile;
		bool changed_settings = !kr_profile_decode(&old_profile, r->peer_wire + 14, r->peer_len - 18) || old_profile.revision != kr_get32(cert + 17) ||
			memcmp(old_profile.snapshot, cert + 133, 16);
		if (changed_owner || changed_settings) {
			enum kr_result retired = kr_abandon_candidate(p->engine, i, changed_owner ? KR_PEER_OWNERSHIP_CHANGED : KR_PEER_SETTINGS_CHANGED);
			if (retired != KR_OK && retired != KR_FULL) { return -EIO; }
		}
	}
	memcpy(bytes, cert, 325); memcpy(bytes + 325, public_key, 32);
	int result = p->io.blob(p->io.ctx, (uint16_t)(500 + slot), bytes, sizeof(bytes), true);
	if (result) { p->engine->storage_error = true; p->engine->ready = false; }
	return result;
}
void kr_product_bind_peer(struct kr_product *p, struct kr_peer_runtime *peers)
{
	p->peers = peers; peers->application = p; peers->allowed = allow_peer; peers->admit = admit_peer; peers->cache_certificate = cache_peer;
}
static bool evidence_matches(const uint8_t *b, const struct kr_record *r)
{
	uint8_t own[32], remote[32]; kr_hash(r->own.data, r->own.data_len, own); kr_hash(r->peer_wire, r->peer_len, remote);
	return !memcmp(b, "KEV2", 4) && kr_get32(b + 4) == r->sequence && kr_get64(b + 8) == r->peer &&
		!memcmp(b + 16, own, 32) && !memcmp(b + 48, remote, 32);
}
static void load_accounts(struct kr_product *p)
{
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &p->engine->records[i];
		if (r->kind && !p->io.blob(p->io.ctx, (uint16_t)(300 + i), evidence, sizeof(evidence), false) &&
			evidence_matches(evidence, r) && evidence[794] && (evidence[437] == 3 || evidence[437] == 4)) {
			remember_peer(p, i, evidence + 437);
		}
	}
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		const struct kr_suppression *s = &p->engine->suppressed[i]; uint8_t ledger[88];
		if (!s->peer) { continue; }
		int got = p->io.blob(p->io.ctx, (uint16_t)(900 + i), ledger, sizeof(ledger), false);
		if (!got && (!memcmp(ledger, "KAS3", 4) || !memcmp(ledger, "KAD3", 4)) && kr_get64(ledger + 4) == s->peer && !memcmp(ledger + 12, s->id, 16)) {
			memcpy(p->accounts[KR_RECORDS + i], ledger + 28, 16); p->scopes[KR_RECORDS + i].epoch = kr_get64(ledger + 44);
			memcpy(p->scopes[KR_RECORDS + i].tag, ledger + 52, 16); p->duplicate_suppression[i] = !memcmp(ledger, "KAD3", 4);
			p->peer_revisions[KR_RECORDS + i] = kr_get32(ledger + 68); memcpy(p->peer_hashes[KR_RECORDS + i], ledger + 72, 16); continue;
		}
		if (!p->io.blob(p->io.ctx, (uint16_t)(900 + i), ledger, 68, false) &&
			(!memcmp(ledger, "KAS2", 4) || !memcmp(ledger, "KAD2", 4)) && kr_get64(ledger + 4) == s->peer && !memcmp(ledger + 12, s->id, 16)) {
			memcpy(p->accounts[KR_RECORDS + i], ledger + 28, 16); p->scopes[KR_RECORDS + i].epoch = kr_get64(ledger + 44);
			memcpy(p->scopes[KR_RECORDS + i].tag, ledger + 52, 16); p->duplicate_suppression[i] = !memcmp(ledger, "KAD2", 4);
		}
		if (!p->io.blob(p->io.ctx, (uint16_t)(900 + i), ledger, 44, false) &&
			(!memcmp(ledger, "KAS1", 4) || !memcmp(ledger, "KAD1", 4)) && kr_get64(ledger + 4) == s->peer && !memcmp(ledger + 12, s->id, 16)) {
			memcpy(p->accounts[KR_RECORDS + i], ledger + 28, 16);
			p->duplicate_suppression[i] = !memcmp(ledger, "KAD1", 4);
		}
		for (unsigned n = 0; n < KR_RECORDS; ++n) {
			const struct kr_record *r = &p->engine->records[n];
			if (r->kind && r->peer == s->peer && !memcmp(r->id, s->id, 16) && has_ref(p->scopes[n].tag)) {
				memcpy(p->accounts[KR_RECORDS + i], p->accounts[n], 16); p->scopes[KR_RECORDS + i] = p->scopes[n];
				p->peer_revisions[KR_RECORDS + i] = p->peer_revisions[n]; memcpy(p->peer_hashes[KR_RECORDS + i], p->peer_hashes[n], 16); break;
			}
		}
	}
}
int kr_product_suppression(struct kr_product *p, unsigned slot, const struct kr_suppression *s)
{
	if (slot >= KR_SUPPRESSIONS) { return -EINVAL; }
	uint8_t ref[16]; memcpy(ref, p->accounts[KR_RECORDS + slot], 16);
	bool duplicate = p->duplicate_suppression[slot];
	struct kr_owner_scope scope = p->scopes[KR_RECORDS + slot];
	uint32_t peer_revision = p->peer_revisions[KR_RECORDS + slot]; uint8_t peer_hash[16]; memcpy(peer_hash, p->peer_hashes[KR_RECORDS + slot], 16);
	if (s->peer && !(s->peer >> 63)) {
		for (unsigned i = 0; i < KR_RECORDS; ++i) {
			const struct kr_record *r = &p->engine->records[i];
			if (r->kind && r->peer == s->peer && !memcmp(r->id, s->id, 16)) {
				memcpy(ref, p->accounts[i], 16); scope = p->scopes[i]; duplicate = kr_retired_reason(r->reason);
				peer_revision = p->peer_revisions[i]; memcpy(peer_hash, p->peer_hashes[i], 16); break;
			}
		}
		if (has_ref(ref) || duplicate) {
			uint8_t ledger[88]; memcpy(ledger, duplicate ? "KAD3" : "KAS3", 4); kr_put64(ledger + 4, s->peer);
			memcpy(ledger + 12, s->id, 16); memcpy(ledger + 28, ref, 16);
			kr_put64(ledger + 44, scope.epoch); memcpy(ledger + 52, scope.tag, 16);
			kr_put32(ledger + 68, peer_revision); memcpy(ledger + 72, peer_hash, 16);
			if (p->io.blob(p->io.ctx, (uint16_t)(900 + slot), ledger, sizeof(ledger), true)) {
				p->engine->storage_error = true; p->engine->ready = false; return -EIO;
			}
		}
	}
	int result = p->suppression(p->record_ctx, slot, s);
	if (!result && !s->peer) { result = p->io.blob(p->io.ctx, (uint16_t)(900 + slot), NULL, 0, true); memset(ref, 0, 16); memset(&scope, 0, sizeof(scope)); duplicate = false; peer_revision = 0; memset(peer_hash, 0, 16); }
	if (!result) {
		memcpy(p->accounts[KR_RECORDS + slot], ref, 16); p->scopes[KR_RECORDS + slot] = scope; p->duplicate_suppression[slot] = duplicate;
		p->peer_revisions[KR_RECORDS + slot] = peer_revision; memcpy(p->peer_hashes[KR_RECORDS + slot], peer_hash, 16);
	}
	else { p->engine->storage_error = true; p->engine->ready = false; }
	return result;
}
int kr_product_record(struct kr_product *p, unsigned slot, const struct kr_record *r)
{
	if (slot >= KR_RECORDS) { return -EINVAL; }
	uint16_t key = (uint16_t)(300 + slot);
	if (r->kind == KR_EMPTY) {
		int result = p->record(p->record_ctx, slot, r);
		if (!result) { result = p->io.blob(p->io.ctx, key, NULL, 0, true); }
		if (!result) { memset(p->accounts[slot], 0, 16); memset(&p->scopes[slot], 0, sizeof(p->scopes[slot])); p->peer_revisions[slot] = 0; memset(p->peer_hashes[slot], 0, 16); }
		return result;
	}
	int got = p->io.blob(p->io.ctx, key, evidence, sizeof(evidence), false);
	if (!got && evidence_matches(evidence, r)) {
		int result = p->record(p->record_ctx, slot, r);
		if (!result && evidence[794] && (evidence[437] == 3 || evidence[437] == 4)) {
			remember_peer(p, slot, evidence + 437);
		}
		return result;
	}
	if (p->peers && p->peers->active && p->peers->own.policy_ready && r->own.data_len == 196) {
		memset(evidence, 0, sizeof(evidence)); memcpy(evidence, "KEV2", 4);
		kr_put32(evidence + 4, r->sequence); kr_put64(evidence + 8, r->peer);
		kr_hash(r->own.data, r->own.data_len, evidence + 16); kr_hash(r->peer_wire, r->peer_len, evidence + 48);
		memcpy(evidence + 80, p->peers->own.certificate, 325); memcpy(evidence + 405, p->peers->own.public_key, 32);
		uint64_t source = p->peers->evidence_peer ? p->peers->evidence_peer : r->peer;
		for (unsigned i = 0; i < KR_PEERS; ++i) {
			const struct kr_peer_session *s = &p->peers->sessions[i];
			if (!s->authenticated || s->remote.device != source) { continue; }
			if (p->io.blob(p->io.ctx, (uint16_t)(500 + i), evidence + 437, KR_PEER_CACHE_BYTES, false)) { return -EIO; }
			uint8_t digest[32]; crypto_blake2b(digest, 32, evidence + 437, 325);
			if (memcmp(digest, s->certificate_hash, 32) || memcmp(evidence + 762, s->public_key, 32)) { return -EILSEQ; }
			evidence[794] = 1; break;
		}
		if (r->kind != KR_REVIEW && !evidence[794]) { return -EILSEQ; }
		if (p->io.blob(p->io.ctx, key, evidence, sizeof(evidence), true)) { return -EIO; }
		if (evidence[794] && (evidence[437] == 3 || evidence[437] == 4)) {
			remember_peer(p, slot, evidence + 437);
		}
	}
	/* Old Lab records remain preserved, without invented official evidence. */
	return p->record(p->record_ctx, slot, r);
}
static const char *export_record(struct kr_product *p, unsigned slot, size_t *length, char hash[65])
{
	const struct kr_record *r = &p->engine->records[slot];
	if (r->kind != KR_CONFIRMED && r->kind != KR_REVIEW) { return "RECORD_NOT_EXPORTABLE"; }
	if (p->io.blob(p->io.ctx, (uint16_t)(300 + slot), evidence, sizeof(evidence), false) ||
		!evidence_matches(evidence, r)) { return "LEGACY_EVIDENCE_UNAVAILABLE"; }
	if ((evidence[80] != 3 && evidence[80] != 4) || memcmp(evidence + 245, p->core->identity.public_key, 32)) { return "INVALID_RECORD_EVIDENCE"; }
	if (r->own.data_len != 196 || r->peer_len > 400) { return "RECORD_TOO_LARGE"; }
	size_t bytes = kr_record_encode(r, export_bytes + 24);
	memcpy(export_bytes, "TER2", 4); kr_put16(export_bytes + 4, (uint16_t)bytes); kr_put16(export_bytes + 6, KR_EVIDENCE_BYTES);
	memcpy(export_bytes + 24 + bytes, evidence, sizeof(evidence));
	uint8_t digest[32]; kr_hash(export_bytes + 24, bytes + sizeof(evidence), digest);
	memcpy(export_bytes + 8, digest, 16); export_bytes[14] = (export_bytes[14] & 15) | 0x50; export_bytes[16] = (export_bytes[16] & 63) | 0x80;
	size_t unsigned_bytes = 24 + bytes + sizeof(evidence); kr_hash(export_bytes, unsigned_bytes, digest);
	static const char domain[] = "TINGLE-ENCOUNTER-V2\n"; uint8_t message[64];
	memcpy(message, domain, sizeof(domain) - 1); memcpy(message + sizeof(domain) - 1, digest, 32);
	crypto_ed25519_sign(export_bytes + unsigned_bytes, p->core->identity.secret, message, sizeof(domain) - 1 + 32);
	*length = unsigned_bytes + 64; kr_hash(export_bytes, *length, digest); kr_hex(digest, 32, hash); return NULL;
}
static int find_sequence(const struct kr_product *p, uint32_t sequence)
{
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		if (p->engine->records[i].kind && p->engine->records[i].sequence == sequence) { return (int)i; }
	}
	return -1;
}
void kr_product_tick(struct kr_product *p)
{
	uint64_t now = kr_product_now(p); if (!now || p->engine->storage_error) { return; }
	/* Missing legacy ownership metadata temporarily gates peer data and
	 * candidates; it must not invalidate an otherwise installed policy. */
	if (p->policy_loaded && !matching_conditions_ready(p)) { p->engine->ready = false; }
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &p->engine->records[i];
		if ((r->kind == KR_CONFIRMED || r->kind == KR_REVIEW) && r->occurred_at &&
			r->own.opportunity_sec && r->expires_at > r->occurred_at &&
			r->expires_at - r->occurred_at == r->own.opportunity_sec && now >= r->expires_at) {
			if (p->io.blob(p->io.ctx, (uint16_t)(300 + i), evidence, sizeof(evidence), false) ||
				!evidence_matches(evidence, r) || (evidence[80] != 3 && evidence[80] != 4) ||
				!kr_peer_grant(evidence + 80, p->server_key, r->occurred_at)) { continue; }
			uint8_t id[16]; memcpy(id, r->id, 16); if (kr_ack(p->engine, id) != KR_OK) { break; }
		}
	}
	bool matching = kr_product_ready(p);
	for (unsigned i = 0; matching && i < KR_SUPPRESSIONS; ++i) {
		const struct kr_suppression *s = &p->engine->suppressed[i]; if (!s->peer) { p->seen[i] = 0; continue; }
		if (p->seen[i] < s->occurred_at) { p->seen[i] = 0; }
		/* A fresh trusted sync plus continuous scanning can observe absence
		 * after reboot even when no old session is restored. */
		if (!p->seen[i]) { p->seen[i] = now; }
		for (unsigned n = 0; n < KR_PEERS; ++n) {
			const struct kr_peer_session *peer = &p->peers->sessions[n];
			if (peer->authenticated && peer->remote.device == s->peer && scope_equal(&p->scopes[KR_RECORDS + i], &peer->remote) &&
				peer->last_seen > p->seen[i]) { p->seen[i] = peer->last_seen; }
		}
		if (p->seen[i] && now > p->seen[i] && now - p->seen[i] > p->policy.presence_seconds) {
			uint8_t marker[24]; memcpy(marker, s->id, 16); kr_put64(marker + 16, s->peer);
			if (p->io.blob(p->io.ctx, (uint16_t)(800 + i), marker, sizeof(marker), true)) { p->engine->storage_error = true; break; }
		}
	}
}

static bool server_signature(const struct kr_product *p, const struct kr_json *j, const char *message, int size)
{
	char key_id[29] = "dev-ed25519-"; uint8_t signature[64]; kr_hex(p->server_key, 8, key_id + 12);
	return size > 0 && size < 1200 && text(j, "serverKeyId", key_id) &&
		kr_base64_decode(kr_json_string(j, "serverSignature"), signature, 64) == 64 &&
		!crypto_ed25519_check(signature, p->server_key, (const uint8_t *)message, (size_t)size);
}
static bool owner_fields(const struct kr_product *p, const struct kr_json *j)
{
	int64_t device, epoch;
	return p->core->current.magic && p->core->reset.phase != KR_RESETTING &&
		kr_positive(kr_json_number(j, "deviceId"), &device) && device == p->core->current.device_id &&
		kr_nonnegative(kr_json_number(j, "ownershipEpoch"), &epoch) && epoch == p->core->reset.ownership_epoch &&
		text(j, "ownerGeneration", p->core->current.owner_generation);
}
static const char *restore_account(struct kr_product *p, const struct kr_json *j)
{
	const char *ref = kr_json_string(j, "ownerRefHex"), *event = kr_json_string(j, "eventId");
	const char *key = kr_json_string(j, "serverKeyId"), *grant = kr_json_string(j, "grantId"); int64_t issued;
	if (!text(j, "proofProtocolVersion", "tingle-account-lock-v2")) { return "PEER_SCOPE_REQUIRED"; }
	if (!owner_fields(p, j) ||
		!ref || strlen(ref) != 32 || strspn(ref, "0123456789abcdef") != 32 || !kr_uuid(event) || !kr_uuid(grant) ||
		!key || !kr_identifier(key) || !kr_positive(kr_json_number(j, "issuedAtEpochSeconds"), &issued) ||
		!kr_product_now(p) || (uint64_t)issued > kr_product_now(p) + 300 || (uint64_t)issued + 300 < kr_product_now(p)) { return "INVALID_ACCOUNT_LOCK_PROOF"; }
	uint8_t owner[16]; for (unsigned i = 0; i < 16; ++i) {
		char byte[3] = {ref[2 * i], ref[2 * i + 1], 0}; unsigned value;
		if (sscanf(byte, "%02x", &value) != 1) { return "INVALID_ACCOUNT_LOCK_PROOF"; } owner[i] = (uint8_t)value;
	}
	if (!has_ref(owner)) { return "INVALID_ACCOUNT_LOCK_PROOF"; }
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		const struct kr_suppression *s = &p->engine->suppressed[i]; char id[37]; uuid_text(s->id, id);
		if (!s->peer || s->peer >> 63 || strcmp(id, event)) { continue; }
		uint8_t credential[KR_PEER_CACHE_BYTES], digest[32]; char credential_hash[65]; struct kr_peer_verified verified;
		if (kr_base64_decode(kr_json_string(j, "peerCredential"), credential, sizeof(credential)) != sizeof(credential) ||
			!kr_peer_certificate(credential, credential + 325, p->server_key, s->occurred_at, &verified) ||
			verified.device != s->peer || memcmp(verified.owner_ref, owner, 16) ||
			(s->peer_revision && verified.revision != s->peer_revision)) { return "INVALID_PEER_CREDENTIAL"; }
		kr_hash(credential, sizeof(credential), digest); kr_hex(digest, 32, credential_hash);
		char message[1200]; int n = snprintf(message, sizeof(message),
			"TINGLE-ACCOUNT-LOCK\ntingle-account-lock-v2\n%s\n%s\n%s\n%" PRId64 "\n%s\n%" PRId64 "\n%s\n%016" PRIx64 "\n%s\n%s\n%" PRId64 "\n%s\n",
			key, p->core->identity.serial, p->core->identity.key_id, p->core->current.device_id, p->core->current.owner_generation,
			p->core->reset.ownership_epoch, event, s->peer, ref, grant, issued, credential_hash);
		if (!server_signature(p, j, message, n)) { return "INVALID_SERVER_PROOF"; }
		if (has_ref(p->accounts[KR_RECORDS + i]) && memcmp(p->accounts[KR_RECORDS + i], owner, 16)) { return "ACCOUNT_LOCK_CONFLICT"; }
		if (has_ref(p->scopes[KR_RECORDS + i].tag) && !scope_equal(&p->scopes[KR_RECORDS + i], &verified)) { return "ACCOUNT_LOCK_CONFLICT"; }
		uint8_t ledger[88]; memcpy(ledger, p->duplicate_suppression[i] ? "KAD3" : "KAS3", 4);
		kr_put64(ledger + 4, s->peer); memcpy(ledger + 12, s->id, 16); memcpy(ledger + 28, owner, 16);
		kr_put64(ledger + 44, verified.ownership_epoch); memcpy(ledger + 52, verified.scope, 16);
		kr_put32(ledger + 68, verified.revision); memcpy(ledger + 72, verified.hash, 16);
		if (p->io.blob(p->io.ctx, (uint16_t)(900 + i), ledger, sizeof(ledger), true)) { return "STORAGE_ERROR"; }
		memcpy(p->accounts[KR_RECORDS + i], owner, 16);
		p->scopes[KR_RECORDS + i].epoch = verified.ownership_epoch; memcpy(p->scopes[KR_RECORDS + i].tag, verified.scope, 16);
		p->peer_revisions[KR_RECORDS + i] = verified.revision; memcpy(p->peer_hashes[KR_RECORDS + i], verified.hash, 16); return NULL;
	}
	return "RECORD_NOT_FOUND";
}
static const char *owner_authorize(struct kr_product *p, const struct kr_json *j)
{
	int64_t issued, expires, permissions;
	if (!p->core->profile_transport_protected) { return "SECURE_TRANSPORT_REQUIRED"; }
	if (!owner_fields(p, j) || !text(j, "proofProtocolVersion", KR_OWNER_PROOF) ||
		!kr_epoch(kr_json_string(j, "serverTime"), &issued) ||
		!kr_positive(kr_json_number(j, "expiresAtEpochSeconds"), &expires) ||
		!kr_positive(kr_json_number(j, "permissions"), &permissions) || permissions > 15 ||
		expires <= issued || expires - issued > 300 || !text(j, "deviceNonce", p->owner_nonce)) { return "INVALID_OWNER_PROOF"; }
	bool fresh = p->challenge_deadline > uptime(p) && p->challenge_connection == p->core->connection_epoch;
	bool retry = p->session_deadline > uptime(p) && p->session_connection == p->core->connection_epoch && p->permissions == (unsigned)permissions;
	if (!fresh && !retry) { return "OWNER_CHALLENGE_REQUIRED"; }
	const char *key_id = kr_json_string(j, "serverKeyId"); if (!key_id || !kr_identifier(key_id)) { return "INVALID_OWNER_PROOF"; }
	char message[1200]; int n = snprintf(message, sizeof(message),
		"TINGLE-OWNER-SESSION\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%" PRId64 "\n%s\n%" PRId64 "\n%" PRId64 "\n%" PRId64 "\n",
		KR_OWNER_PROOF, key_id, p->core->identity.serial, p->core->identity.key_id, p->core->current.device_id,
		p->core->current.owner_generation, p->core->reset.ownership_epoch, p->owner_nonce, permissions, issued, expires);
	if (!server_signature(p, j, message, n)) { return "INVALID_OWNER_PROOF"; }
	uint64_t now = kr_product_now(p); if (now && (uint64_t)expires <= now) { return "OWNER_PROOF_EXPIRED"; }
	set_time(p, issued); now = kr_product_now(p); if ((uint64_t)expires <= now) { return "OWNER_PROOF_EXPIRED"; }
	p->permissions = (unsigned)permissions; p->session_connection = p->core->connection_epoch;
	p->session_epoch = p->core->reset.ownership_epoch; p->session_device = p->core->current.device_id;
	strcpy(p->session_owner, p->core->current.owner_generation);
	p->session_deadline = uptime(p) + (expires - (int64_t)now) * 1000; p->challenge_deadline = 0; return NULL;
}
static const char *receipt(struct kr_product *p, const struct kr_json *j, bool release)
{
	if (!owner_fields(p, j)) { return "OWNER_MISMATCH"; }
	const char *event = kr_json_string(j, "eventId"), *submission = kr_json_string(j, "submissionId");
	const char *hash = kr_json_string(j, "envelopeHash"), *receipt_id = kr_json_string(j, "receiptId");
	const char *key_id = kr_json_string(j, "serverKeyId"); int64_t received;
	if (!kr_uuid(event) || !kr_uuid(receipt_id) || !key_id || !kr_identifier(key_id) ||
		!kr_positive(kr_json_number(j, "receivedAtEpochSeconds"), &received) ||
		!kr_product_now(p) || (uint64_t)received > kr_product_now(p) + 300) { return "INVALID_RECEIPT"; }
	char message[1200]; int n;
	uint64_t peer = 0;
	if (release) {
		const char *ref = kr_json_string(j, "peerDeviceRefHex"), *result = kr_json_string(j, "result");
		if (!ref || strlen(ref) != 16 || strspn(ref, "0123456789abcdef") != 16 ||
			!result || (strcmp(result, "RELEASE_AFTER_DEPARTURE") && strcmp(result, "ERROR_REVIEWED")) ||
			!text(j, "proofProtocolVersion", "tingle-record-release-v2")) { return "INVALID_RELEASE"; }
		for (unsigned i = 0; i < 16; ++i) { peer = (peer << 4) | (uint64_t)(ref[i] <= '9' ? ref[i] - '0' : ref[i] - 'a' + 10); }
		if (!peer || (!strcmp(result, "ERROR_REVIEWED") != ((peer >> 63) != 0))) { return "INVALID_RELEASE"; }
		n = snprintf(message, sizeof(message),
			"TINGLE-RECORD-RELEASE\ntingle-record-release-v2\n%s\n%s\n%s\n%" PRId64 "\n%s\n%" PRId64 "\n%s\n%s\n%s\n%" PRId64 "\n%s\n",
			key_id, p->core->identity.serial, p->core->identity.key_id, p->core->current.device_id,
			p->core->current.owner_generation, p->core->reset.ownership_epoch, event, ref, receipt_id, received, result);
	} else {
		if (!kr_uuid(submission) || !hash || strlen(hash) != 64 || strspn(hash, "0123456789abcdef") != 64 ||
			!text(j, "receiptStatus", "STORED") || !text(j, "deletePermission", "DELETE_ORIGINAL") ||
			!text(j, "proofProtocolVersion", KR_RECEIPT_PROOF)) { return "INVALID_RECEIPT"; }
		n = snprintf(message, sizeof(message),
			"TINGLE-RECORD-RECEIPT\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\nSTORED\nDELETE_ORIGINAL\n",
			KR_RECEIPT_PROOF, key_id, p->core->identity.serial, p->core->identity.key_id, p->core->current.device_id,
			p->core->current.owner_generation, p->core->reset.ownership_epoch, event, submission, hash, receipt_id, received);
	}
	if (!server_signature(p, j, message, n)) { return "INVALID_SERVER_PROOF"; }
	if (release) {
		for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
			const struct kr_suppression *s = &p->engine->suppressed[i]; char id[37]; uuid_text(s->id, id);
			if (strcmp(id, event)) { continue; }
			if (s->peer != peer) { return "RELEASE_CONFLICT"; }
			uint8_t marker[24];
			if (!(peer >> 63) && (p->io.blob(p->io.ctx, (uint16_t)(800 + i), marker, sizeof(marker), false) ||
				memcmp(marker, s->id, 16) || kr_get64(marker + 16) != s->peer)) { return "DEPARTURE_NOT_CONFIRMED"; }
			/* After the original opportunity deadline the missing final radio
			 * receipt must not make a server-approved release impossible forever. */
			if (s->phase != KR_COMPLETE && s->expires_at && kr_product_now(p) >= s->expires_at) {
				struct kr_suppression finished = *s; finished.phase = KR_COMPLETE;
				if (p->engine->io.suppression(p->engine->io.ctx, i, &finished)) { return "STORAGE_ERROR"; }
				p->engine->suppressed[i] = finished;
			}
			uint8_t copy[16]; memcpy(copy, s->id, 16);
			if (kr_release(p->engine, copy) != KR_OK) { return "RELEASE_NOT_READY"; }
			p->seen[i] = 0; return p->io.blob(p->io.ctx, (uint16_t)(800 + i), NULL, 0, true) ? "STORAGE_ERROR" : NULL;
		}
		return NULL;
	}
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &p->engine->records[i]; if (r->kind != KR_CONFIRMED && r->kind != KR_REVIEW) { continue; }
		char id[37]; uuid_text(r->id, id); if (strcmp(id, event)) { continue; }
		size_t length; char actual[65], sid[37]; const char *failure = export_record(p, i, &length, actual);
		if (failure) { return failure; } uuid_text(export_bytes + 8, sid);
		if (strcmp(actual, hash) || strcmp(sid, submission)) { return "RECEIPT_CONFLICT"; }
		uint8_t copy[16]; memcpy(copy, r->id, 16); return kr_ack(p->engine, copy) == KR_OK ? NULL : "STORAGE_ERROR";
	}
	/* A valid server receipt for an already removed original is idempotent;
	 * it never selects another record by slot, cursor or current settings. */
	return NULL;
}
bool kr_product_dispatch(struct kr_product *p, const struct kr_json *j, const char *request,
	struct kr_status status, char response[KR_RESPONSE_MAX])
{
	const char *type = kr_json_string(j, "type"), *failure = NULL;
	char cursor[33]; kr_hex(p->engine->cursor_epoch, 16, cursor);
	if (!type) { return false; }
	if (!strcmp(type, "GET_DEVICE_INFO")) {
		uint8_t spki[44] = {0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00}; char encoded[60];
		memcpy(spki + 12, p->core->identity.public_key, 32); kr_base64_encode(spki, sizeof(spki), encoded);
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"DEVICE_INFO\",\"requestId\":%s,\"deviceSerial\":\"%s\",\"keyId\":\"%s\","
			"\"publicKeySpkiBase64url\":\"%s\",\"firmwareVersion\":\"%s\",\"supportedProtocolVersions\":[1],"
			"\"supportedFunctions\":[\"GET_DEVICE_INFO\",\"GET_STATUS\",\"GET_REGISTRATION_PROOF\",\"GET_SYNC_CHALLENGE\",\"SYNC_SETTINGS\",\"GET_SETTINGS_APPLIED_PROOF\","
			"\"GET_OWNER_CHALLENGE\",\"AUTHORIZE_OWNER\",\"GET_RECORD\",\"READ_RECORD_CHUNK\",\"ACK_RECORD\",\"RELEASE_RECORD\","
			"\"STOP_FEEDBACK\",\"RESTORE_ACCOUNT_LOCK\",\"GET_RESET_CHALLENGE\",\"RESET_DEVICE\",\"GET_RESET_RESULT\","
			"\"GET_PUBLIC_NAME\",\"SET_PUBLIC_NAME\",\"CLEAR_PUBLIC_NAME\"],\"maxPublicNameBytes\":%u,"
			"\"supportedPayloadFormats\":[\"%s\",\"%s\",\"%s\",\"%s\"],\"ownershipEpoch\":%" PRId64 ","
			"\"ownerState\":\"%s\",\"supportedProofProtocolVersions\":[\"%s\",\"%s\"],\"resetContractVersion\":\"%s\","
			"\"maxFrameBytes\":%u,\"maxPayloadBytes\":%u,\"maxResponseBytes\":%u,\"storedSettingsVersion\":%" PRId64 ",\"storedPayloadHash\":\"%s\","
			"\"storedPayloadFormat\":\"%s\",\"storedPayloadBytes\":%u,\"profileStorageCharacteristic\":\"6e400004-b5a3-f393-e0a9-e50e24dcca9e\","
			"\"profileRequiresEncryption\":true,\"peerExchangeProtocol\":\"tingle-peer-v3\",\"peerOwnerScopeVersion\":1,"
			"\"accountLockProofVersion\":\"tingle-account-lock-v2\",\"maxPeerSessions\":16,\"recordCursorEpoch\":\"%s\","
			"\"multiKeyringAccounts\":true,\"applied\":%s,\"timeReady\":%s,\"matchingReady\":%s,\"storageHealthy\":%s%s}\n",
			request, p->core->identity.serial, p->core->identity.key_id, encoded, KR_FIRMWARE_VERSION,
			KR_PUBLIC_NAME_MAX,
			KR_DEV_PAYLOAD_FORMAT, KR_PROFILE_STORAGE_FORMAT, KR_POLICY_FORMAT, KR_POLICY_LEGACY_FORMAT, p->core->reset.ownership_epoch,
			p->core->reset.phase == KR_RESETTING ? "RESETTING" : p->core->current.magic ? "BOUND" : "UNOWNED",
			KR_PROOF_VERSION, KR_PROOF_VERSION_2, KR_RESET_VERSION, KR_FRAME_MAX, KR_PAYLOAD_MAX, KR_RESPONSE_MAX,
			p->core->current.version, p->core->current.hash, p->core->current.format, p->core->current.payload_size, cursor,
			kr_product_applied(p) ? "true" : "false", p->clock_ready ? "true" : "false", kr_product_ready(p) ? "true" : "false",
			p->core->storage_error || p->core->settings_recovery_required || p->engine->storage_error ? "false" : "true",
			p->core->settings_recovery_required ? ",\"settingsRecoveryRequired\":true" : ""); return true;
	}
	if (!strcmp(type, "GET_STATUS")) {
		char mv[20], battery[12], total[32] = "null", used[32] = "null", pending[16] = "null", review[16] = "null", candidates[16] = "null", missing[16] = "null";
		char charge_current[12] = "null", usb_seconds[16] = "null";
		if (status.charge_current_ma > 0) { snprintf(charge_current, sizeof(charge_current), "%d", status.charge_current_ma); }
		if (status.usb_valid) { snprintf(usb_seconds, sizeof(usb_seconds), "%" PRIu32, status.charge_elapsed_seconds); }
		if (authorized(p, KR_OWNER_READ)) { snprintf(missing, sizeof(missing), "%u", missing_accounts(p)); }
		if (status.battery_mv >= 0) { snprintf(mv, sizeof(mv), "%d", status.battery_mv); } else { strcpy(mv, "null"); }
		struct kr_battery_reading reading = kr_battery_reading(status);
		int estimate = reading.percent; if (estimate >= 0) { snprintf(battery, sizeof(battery), "%d", estimate); } else { strcpy(battery, "null"); }
		const char *state = "UNKNOWN";
		if (authorized(p, KR_OWNER_READ)) {
			int64_t capacity, occupied;
			if (p->engine->storage_error || p->core->storage_error || p->io.space(p->io.ctx, &capacity, &occupied)) { state = "ERROR"; }
			else {
				snprintf(total, sizeof(total), "%" PRId64, capacity); snprintf(used, sizeof(used), "%" PRId64, occupied);
				snprintf(pending, sizeof(pending), "%u", kr_count(p->engine, KR_CONFIRMED));
				snprintf(review, sizeof(review), "%u", kr_count(p->engine, KR_REVIEW)); snprintf(candidates, sizeof(candidates), "%u", kr_count(p->engine, KR_MATCH));
				state = !kr_can_insert(p->engine, KR_MATCH) || kr_count(p->engine, KR_CONFIRMED) >= KR_CONFIRMED_LIMIT || kr_count(p->engine, KR_REVIEW) >= KR_REVIEW_LIMIT ||
					capacity - occupied < 4096 ? "FULL" : "AVAILABLE";
			}
		}
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"STATUS\",\"requestId\":%s,\"batteryPercent\":%s,\"batteryMillivolts\":%s,"
			"\"batteryPercentSource\":\"%s\",\"batteryPresence\":\"%s\",\"powerSource\":\"%s\",\"charging\":%s,"
			"\"batteryProtectionState\":\"%s\",\"chargeMonitorState\":\"%s\",\"configuredChargeCurrentMa\":%s,\"usbConnectedSeconds\":%s,\"storageTotalBytes\":%s,"
			"\"storageUsedBytes\":%s,\"storageState\":\"%s\",\"pendingRecordCount\":%s,\"reviewRecordCount\":%s,\"candidateCount\":%s,"
			"\"ownerAuthorized\":%s,\"unresolvedAccountLockCount\":%s,\"recordCursorEpoch\":\"%s\",\"applied\":%s,\"timeReady\":%s,\"matchingReady\":%s,\"storedSettingsVersion\":%" PRId64 "%s}\n",
			request, battery, mv, reading.source, reading.presence, reading.power,
			status.presence_valid && !status.battery_present ? "false" : status.charging < 0 ? "null" : status.charging ? "true" : "false",
			status.battery_power_state ? status.battery_power_state : "MEASUREMENT_UNKNOWN",
			status.charge_monitor_state ? status.charge_monitor_state : "UNKNOWN", charge_current, usb_seconds,
			total, used, state, pending, review, candidates, authorized(p, KR_OWNER_READ) ? "true" : "false",
			missing, cursor, kr_product_applied(p) ? "true" : "false",
			p->clock_ready ? "true" : "false", kr_product_ready(p) ? "true" : "false", p->core->current.version,
			p->core->settings_recovery_required ? ",\"settingsRecoveryRequired\":true" : ""); return true;
	}
	if (!strcmp(type, "GET_PUBLIC_NAME") || !strcmp(type, "SET_PUBLIC_NAME") || !strcmp(type, "CLEAR_PUBLIC_NAME")) {
		public_name_command(p, j, type, request, response);
		return true;
	}
	if (!strcmp(type, "GET_OWNER_CHALLENGE")) {
		uint8_t nonce[32];
		if (!p->core->profile_transport_protected) { failure = "SECURE_TRANSPORT_REQUIRED"; }
		else if (!p->core->current.magic || p->core->reset.phase == KR_RESETTING) { failure = "OWNER_NOT_BOUND"; }
		else if (p->io.random(p->io.ctx, nonce, sizeof(nonce))) { failure = "RANDOM_ERROR"; }
		else {
			kr_base64_encode(nonce, sizeof(nonce), p->owner_nonce); p->challenge_deadline = uptime(p) + 60000; p->challenge_connection = p->core->connection_epoch;
			snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"OWNER_CHALLENGE\",\"requestId\":%s,\"deviceNonce\":\"%s\",\"expiresInSeconds\":60}\n", request, p->owner_nonce); return true;
		}
	} else if (!strcmp(type, "AUTHORIZE_OWNER")) {
		failure = owner_authorize(p, j);
		if (!failure) { snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"OWNER_RESULT\",\"requestId\":%s,\"authorized\":true,\"permissions\":%u}\n", request, p->permissions); return true; }
	} else if (!strcmp(type, "RESTORE_ACCOUNT_LOCK")) {
		failure = authorized(p, KR_OWNER_CONTROL) ? restore_account(p, j) : "OWNER_SESSION_REQUIRED";
		if (!failure) { snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"ACCOUNT_LOCK_RESULT\",\"requestId\":%s,\"restored\":true}\n", request); return true; }
	} else if (!strcmp(type, "GET_RECORD") || !strcmp(type, "READ_RECORD_CHUNK")) {
		if (!authorized(p, KR_OWNER_READ)) { failure = "OWNER_SESSION_REQUIRED"; }
		else if (p->engine->storage_error) { failure = "STORAGE_ERROR"; }
		else {
			int64_t sequence; int slot = -1;
			if (!strcmp(type, "GET_RECORD")) {
				if (!kr_nonnegative(kr_json_number(j, "afterSequence"), &sequence) || sequence > UINT32_MAX) { failure = "INVALID_CURSOR"; }
				else if (kr_json_string(j, "recordCursorEpoch") && !text(j, "recordCursorEpoch", cursor)) { failure = "CURSOR_EPOCH_MISMATCH"; }
				else if (sequence && !kr_json_string(j, "recordCursorEpoch")) { failure = "CURSOR_EPOCH_REQUIRED"; }
				else {
					for (unsigned i = 0; i < KR_RECORDS; ++i) {
						const struct kr_record *r = &p->engine->records[i];
						if ((r->kind == KR_CONFIRMED || r->kind == KR_REVIEW) && r->sequence > (uint32_t)sequence &&
							(slot < 0 || r->sequence < p->engine->records[slot].sequence)) { slot = (int)i; }
					}
					if (slot < 0) { snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"RECORD_END\",\"requestId\":%s,\"recordCursorEpoch\":\"%s\"}\n", request, cursor); return true; }
				}
			} else if (!kr_positive(kr_json_number(j, "sequence"), &sequence) || sequence > UINT32_MAX) { failure = "INVALID_CURSOR"; }
			else { slot = find_sequence(p, (uint32_t)sequence); if (slot < 0) { failure = "RECORD_NOT_FOUND"; } }
			if (!failure) {
				size_t length; char hash[65]; failure = export_record(p, (unsigned)slot, &length, hash);
				if (failure && !strcmp(type, "GET_RECORD")) {
					snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"ERROR\",\"requestId\":%s,\"code\":\"%s\",\"sequence\":%u}\n",
						request, failure, p->engine->records[slot].sequence); return true;
				}
				if (!failure && !strcmp(type, "GET_RECORD")) {
					const struct kr_record *r = &p->engine->records[slot]; char id[37], sid[37], snapshot[37], owner[37], peer[480];
					uuid_text(r->id, id); uuid_text(export_bytes + 8, sid); uuid_text(evidence + 125, snapshot); uuid_text(evidence + 109, owner);
					if (evidence[794]) { kr_base64_encode(evidence + 437, KR_PEER_CACHE_BYTES, peer); } else { peer[0] = 0; }
					snprintf(response, KR_RESPONSE_MAX,
						"{\"protocolVersion\":1,\"type\":\"RECORD\",\"requestId\":%s,\"recordCursorEpoch\":\"%s\",\"sequence\":%u,\"kind\":\"%s\",\"eventId\":\"%s\","
						"\"submissionId\":\"%s\",\"deviceId\":%" PRIu64 ",\"ownerGeneration\":\"%s\",\"ownershipEpoch\":%" PRIu64 ","
						"\"occurredAtEpochSeconds\":%" PRIu64 ",\"expiresAtEpochSeconds\":%" PRIu64 ",\"policyVersion\":\"tingle-policy-v2\","
						"\"matchingRuleVersion\":\"r2-c-20261106\",\"settingsSnapshotId\":\"%s\",\"peerCredential\":\"%s\","
						"\"evidenceProtocolVersion\":\"%s\",\"totalBytes\":%u,\"envelopeHash\":\"%s\"}\n",
						request, cursor, r->sequence, r->kind == KR_CONFIRMED ? "CONFIRMED" : "REVIEW", id, sid, kr_get64(evidence + 89), owner,
						evidence[80] == 4 ? (uint64_t)kr_get32(evidence + 101) : kr_get64(evidence + 101),
						r->occurred_at, r->expires_at, snapshot, peer, KR_ENCOUNTER_PROOF, (unsigned)length, hash); return true;
				} else if (!failure) {
					int64_t offset, requested;
					if (!kr_nonnegative(kr_json_number(j, "offset"), &offset) || !kr_positive(kr_json_number(j, "length"), &requested) ||
						requested > 512 || (uint64_t)offset >= length || !text(j, "envelopeHash", hash)) { failure = "RECORD_READ_CONFLICT"; }
					else {
						size_t count = length - (size_t)offset; if (count > (size_t)requested) { count = (size_t)requested; } char encoded[684];
						kr_base64_encode(export_bytes + offset, count, encoded);
						snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"RECORD_CHUNK\",\"requestId\":%s,\"sequence\":%u,\"offset\":%" PRId64 ",\"totalBytes\":%u,\"envelopeHash\":\"%s\",\"data\":\"%s\"}\n",
							request, p->engine->records[slot].sequence, offset, (unsigned)length, hash, encoded); return true;
					}
				}
			}
		}
	} else if (!strcmp(type, "ACK_RECORD") || !strcmp(type, "RELEASE_RECORD")) {
		bool release = !strcmp(type, "RELEASE_RECORD");
		if (!authorized(p, release ? KR_OWNER_RELEASE : KR_OWNER_ACK)) { failure = "OWNER_SESSION_REQUIRED"; }
		else { failure = receipt(p, j, release); }
		if (!failure) { snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"%s\",\"requestId\":%s,\"result\":\"%s\"}\n",
			release ? "RELEASE_RESULT" : "RECORD_ACK", request, release ? "RELEASED" : "REMOVED_OR_ALREADY_REMOVED"); return true; }
	} else if (!strcmp(type, "STOP_FEEDBACK")) {
		if (!authorized(p, KR_OWNER_CONTROL)) { failure = "OWNER_SESSION_REQUIRED"; }
		else { if (p->io.stop_feedback) { p->io.stop_feedback(p->io.ctx); }
			snprintf(response, KR_RESPONSE_MAX, "{\"protocolVersion\":1,\"type\":\"FEEDBACK_RESULT\",\"requestId\":%s,\"stopped\":true}\n", request); return true; }
	} else { return false; }
	error(response, request, failure ? failure : "INVALID_MESSAGE"); return true;
}

#include "registration.h"
#include "monocypher-ed25519.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

bool kr_nonnegative(const char *text, int64_t *out)
{
	if (text && !strcmp(text, "0")) { *out = 0; return true; }
	return kr_positive(text, out);
}

const char *kr_reset_policy_name(uint32_t policy)
{
	return policy == KR_RESET_DISCARD ? "DISCARD_WITH_CONSENT" : "REQUIRE_SYNCED";
}

static int receipt_text(const struct kr_core *core, const struct kr_reset_record *r,
	char out[1024])
{
	return snprintf(out, 1024,
		"TINGLE-KEYRING-RESET-CLEARED\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n"
		"%" PRId64 "\n%" PRId64 "\n%s\n%s\n%s\nLOGICAL\nCLEARED\n",
		KR_RESET_VERSION, core->identity.key_id, core->identity.serial, r->device_id,
		r->owner_generation, r->run_id, r->from_epoch, r->ownership_epoch,
		r->nonce, KR_RESET_SCOPE, kr_reset_policy_name(r->policy));
}

static bool sign_message(const struct kr_core *core, const char *text, int size, char out[87])
{
	if (size < 0 || size >= 1024) { return false; }
	uint8_t signature[64];
	crypto_ed25519_sign(signature, core->identity.secret, (const uint8_t *)text, (size_t)size);
	kr_base64_encode(signature, sizeof(signature), out);
	return true;
}

bool kr_reset_record_valid(const struct kr_core *core)
{
	const struct kr_reset_record *r = &core->reset;
	if (r->magic != KR_RESET_MAGIC || r->ownership_epoch < 0 || r->phase > KR_RESET_DONE) {
		return false;
	}
	if (r->phase == KR_RESET_NONE) {
		return r->ownership_epoch == 0 && r->from_epoch == 0 && r->device_id == 0 &&
			r->policy == 0 && !r->run_id[0] && !r->owner_generation[0] && !r->nonce[0] && !r->signature[0];
	}
	if (r->from_epoch < 0 || r->from_epoch == INT64_MAX ||
		r->ownership_epoch != r->from_epoch + 1 || r->device_id <= 0 ||
		r->policy > KR_RESET_DISCARD || r->run_id[36] || r->owner_generation[36] ||
		r->nonce[43] || r->signature[86] || !kr_uuid(r->run_id) ||
		!kr_uuid(r->owner_generation) || !kr_nonce(r->nonce)) { return false; }
	if (r->phase == KR_RESETTING) { return r->signature[0] == 0; }
	uint8_t signature[64];
	char text[1024];
	int size = receipt_text(core, r, text);
	return size > 0 && size < (int)sizeof(text) &&
		kr_base64_decode(r->signature, signature, sizeof(signature)) == 64 &&
		!crypto_ed25519_check(signature, core->identity.public_key, (const uint8_t *)text, (size_t)size);
}

const char *kr_reset_issue(struct kr_core *core, const uint8_t bytes[48], int64_t now_ms,
	char signature[87])
{
	if (core->storage_error) { return "STORAGE_ERROR"; }
	if (core->reset.phase == KR_RESETTING) { return "RESET_IN_PROGRESS"; }
	struct kr_reset_challenge *c = &core->reset_challenge;
	if (!c->deadline_ms || now_ms >= c->deadline_ms) {
		char hex[33];
		kr_hex(bytes, 16, hex);
		snprintf(c->id, sizeof(c->id), "%.8s-%.4s-4%.3s-8%.3s-%.12s",
			hex, hex + 8, hex + 13, hex + 17, hex + 20);
		kr_base64_encode(bytes + 16, 32, c->nonce);
		c->deadline_ms = now_ms + KR_RESET_SECONDS * 1000;
	}
	char text[1024];
	int size = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-RESET-CHALLENGE\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n60\n",
		KR_RESET_VERSION, core->identity.key_id, core->identity.serial,
		core->reset.ownership_epoch, core->current.magic ? core->current.owner_generation : "NONE",
		c->id, c->nonce);
	return sign_message(core, text, size, signature) ? NULL : "PROOF_ERROR";
}

bool kr_reset_query_matches(const struct kr_core *core, const char *run, const char *nonce)
{
	return core->reset.phase != KR_RESET_NONE && run && nonce &&
		!strcmp(run, core->reset.run_id) && !strcmp(nonce, core->reset.nonce);
}

const char *kr_reset_begin(struct kr_core *core, const struct kr_json *json,
	int64_t now_ms, const char *server_key_id, const uint8_t server_key[32])
{
	if (core->storage_error) { return "STORAGE_ERROR"; }
	const char *run = kr_json_string(json, "resetRunId");
	const char *nonce = kr_json_string(json, "resetNonce");
	/* The old result survives a new owner binding. This branch never erases again. */
	if (kr_reset_query_matches(core, run, nonce)) { return NULL; }
	if (core->reset.phase == KR_RESETTING) { return "RESET_IN_PROGRESS"; }
	const char *owner = kr_json_string(json, "ownerGeneration");
	const char *key = kr_json_string(json, "serverKeyId");
	const char *policy = kr_json_string(json, "recordPolicy");
	const char *challenge_id = kr_json_string(json, "challengeId");
	const char *device_nonce = kr_json_string(json, "deviceNonce");
	struct kr_reset_record next = {.magic = KR_RESET_MAGIC, .phase = KR_RESETTING};
	const char *version = kr_json_string(json, "resetProtocolVersion");
	const char *scope = kr_json_string(json, "deletionScope");
	const char *key_id = kr_json_string(json, "keyId");
	const char *serial = kr_json_string(json, "deviceSerial");
	if (!kr_uuid(run) || !kr_uuid(owner) || !kr_nonce(nonce) || !key ||
		strcmp(key, server_key_id) || !version || strcmp(version, KR_RESET_VERSION) ||
		!scope || strcmp(scope, KR_RESET_SCOPE) || !policy || !kr_identifier(server_key_id) ||
		!key_id || strcmp(key_id, core->identity.key_id) || !serial ||
		strcmp(serial, core->identity.serial) ||
		!kr_positive(kr_json_number(json, "deviceId"), &next.device_id) ||
		!kr_nonnegative(kr_json_number(json, "fromOwnershipEpoch"), &next.from_epoch) ||
		!kr_positive(kr_json_number(json, "toOwnershipEpoch"), &next.ownership_epoch)) {
		return "INVALID_RESET_PROOF";
	}
	if (!strcmp(policy, "DISCARD_WITH_CONSENT")) { next.policy = KR_RESET_DISCARD; }
	else if (strcmp(policy, "REQUIRE_SYNCED")) { return "INVALID_RESET_PROOF"; }
	if (next.from_epoch != core->reset.ownership_epoch || next.from_epoch == INT64_MAX ||
		next.ownership_epoch != next.from_epoch + 1) { return "OWNERSHIP_EPOCH_MISMATCH"; }
	if (core->current.magic && (next.device_id != core->current.device_id ||
		strcmp(owner, core->current.owner_generation))) { return "OWNER_GENERATION_MISMATCH"; }
	struct kr_reset_challenge *c = &core->reset_challenge;
	if (!c->deadline_ms || now_ms >= c->deadline_ms || !challenge_id || !device_nonce ||
		strcmp(challenge_id, c->id) || strcmp(device_nonce, c->nonce)) {
		return "RESET_CHALLENGE_REQUIRED";
	}
	char text[1024];
	int size = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-RESET-AUTH\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n"
		"%" PRId64 "\n%" PRId64 "\n%s\n%s\n%s\n%s\n%s\n",
		KR_RESET_VERSION, key, core->identity.key_id, core->identity.serial,
		next.device_id, owner, run, next.from_epoch, next.ownership_epoch,
		challenge_id, device_nonce, nonce, KR_RESET_SCOPE, policy);
	uint8_t signature[64];
	if (size < 0 || size >= (int)sizeof(text) ||
		kr_base64_decode(kr_json_string(json, "serverSignature"), signature, sizeof(signature)) != 64 ||
		crypto_ed25519_check(signature, server_key, (const uint8_t *)text, (size_t)size)) {
		return "INVALID_RESET_PROOF";
	}
	strcpy(next.run_id, run); strcpy(next.nonce, nonce); strcpy(next.owner_generation, owner);
	if (!core->platform.begin_reset) { return "RESET_UNSUPPORTED"; }
	const char *error = core->platform.begin_reset(core->platform.ctx, &next);
	if (error) {
		if (!strcmp(error, "STORAGE_ERROR")) { core->storage_error = core->stop_peer = true; }
		return error;
	}
	core->reset = next;
	core->reset_challenge.deadline_ms = 0;
	core->stop_peer = true;
	core->settings_apply_state = KR_SETTINGS_APPLY_NONE;
	memset(&core->peer_after_stored, 0, sizeof(core->peer_after_stored));
	return NULL;
}

const char *kr_reset_resume(struct kr_core *core)
{
	if (core->reset.phase != KR_RESETTING) { return NULL; }
	core->stop_peer = true;
	if (core->storage_error) { return "STORAGE_ERROR"; }
	if (!core->platform.erase_owner || !core->platform.save_reset) { return "RESET_UNSUPPORTED"; }
	const char *error = core->platform.erase_owner(core->platform.ctx);
	if (error) { return error; }
	struct kr_reset_record next = core->reset;
	next.phase = KR_RESET_DONE;
	char text[1024];
	int size = receipt_text(core, &next, text);
	if (!sign_message(core, text, size, next.signature)) { return "PROOF_ERROR"; }
	if (core->platform.save_reset(core->platform.ctx, &next)) {
		core->storage_error = true;
		return "STORAGE_ERROR";
	}
	core->reset = next;
	core->settings_apply_state = KR_SETTINGS_APPLY_NONE;
	memset(&core->current, 0, sizeof(core->current));
	memset(&core->peer_after_stored, 0, sizeof(core->peer_after_stored));
	return NULL;
}

#include "registration.h"
#include "service.h"
#include "monocypher-ed25519.h"
#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Synthetic public test material, never linked into the firmware. */
static const char owner[] = "11111111-1111-4111-8111-111111111111";
static const char next_owner[] = "44444444-4444-4444-8444-444444444444";
static const char run[] = "22222222-2222-4222-8222-222222222222";
static const char nonce[] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
static const char server_id[] = "synthetic-server";
struct fixture {
	struct kr_core core;
	struct kr_reset_record journal;
	struct kr_settings disk;
	struct kr_settings_nonce sync;
	uint8_t server_secret[64], server_public[32];
	int pending, fail_intent, fail_final, fail_erase;
	unsigned erased;
	bool user_data, bonds, blocked;
	int64_t now;
	uint32_t generation;
	int radio_failure, phone_failure;
	unsigned stop_calls, finish_calls;
	bool stale_once, reset_on_apply;
};

static int save_settings(void *ctx, const struct kr_settings *next)
{
	struct fixture *f = ctx;
	f->disk = *next;
	return 0;
}

static const char *authorize(void *ctx, const struct kr_settings *next,
	const struct kr_settings_auth *auth, struct kr_peer_plan *plan)
{
	struct fixture *f = ctx;
	const char *error = kr_verify_dev_settings(&f->core.identity, next, auth, &f->sync,
		f->now, server_id, f->server_public, &plan->verified_time);
	if (!error && next->payload[0]) { plan->length = 17; }
	return error;
}

static int save_reset(void *ctx, const struct kr_reset_record *next)
{
	struct fixture *f = ctx;
	assert(next->phase == KR_RESET_DONE && !f->user_data && !f->bonds && !f->disk.magic);
	if (f->fail_final == 1) { return -1; }
	f->journal = *next;
	return f->fail_final == 2 ? -1 : 0;
}

static const char *begin_reset(void *ctx, const struct kr_reset_record *next)
{
	struct fixture *f = ctx;
	if (f->pending < 0) { return "RECORD_STATE_UNKNOWN"; }
	if (f->pending && next->policy == KR_RESET_REQUIRE_SYNCED) { return "PENDING_RECORDS"; }
	f->blocked = true;
	++f->generation;
	if (f->fail_intent == 1) { return "STORAGE_ERROR"; }
	f->journal = *next;
	return f->fail_intent == 2 ? "STORAGE_ERROR" : NULL;
}

static const char *erase_owner(void *ctx)
{
	struct fixture *f = ctx;
	assert(f->blocked && f->journal.phase == KR_RESETTING && f->journal.ownership_epoch > 0);
	++f->erased;
	f->user_data = false;
	if (f->fail_erase == 1) { return "STORAGE_ERROR"; }
	memset(&f->disk, 0, sizeof(f->disk));
	if (f->fail_erase == 2) { return "BOND_CLEAR_FAILED"; }
	f->bonds = false;
	return NULL;
}

static const char *challenge(void *ctx, char signature[87])
{
	struct fixture *f = ctx;
	uint8_t bytes[48];
	for (size_t i = 0; i < sizeof(bytes); ++i) { bytes[i] = (uint8_t)(i + f->now); }
	return kr_reset_issue(&f->core, bytes, f->now, signature);
}

static const char *accept(void *ctx, const struct kr_json *json)
{
	struct fixture *f = ctx;
	return kr_reset_begin(&f->core, json, f->now, server_id, f->server_public);
}

static void reboot(struct fixture *f)
{
	struct kr_identity identity = f->core.identity;
	memset(&f->core, 0, sizeof(f->core));
	f->core.identity = identity;
	f->core.reset = f->journal;
	assert(kr_reset_record_valid(&f->core));
	if (f->journal.phase != KR_RESETTING) { f->core.current = f->disk; }
	f->core.platform = (struct kr_platform){.ctx = f, .save = save_settings, .authorize = authorize,
		.begin_reset = begin_reset, .save_reset = save_reset, .erase_owner = erase_owner,
		.issue_reset_challenge = challenge, .accept_reset = accept};
}

static void init(struct fixture *f)
{
	memset(f, 0, sizeof(*f));
	uint8_t seed[32];
	memset(seed, 0x42, sizeof(seed));
	crypto_ed25519_key_pair(f->core.identity.secret, f->core.identity.public_key, seed);
	memset(seed, 0x24, sizeof(seed));
	crypto_ed25519_key_pair(f->server_secret, f->server_public, seed);
	strcpy(f->core.identity.serial, "synthetic-device");
	strcpy(f->core.identity.key_id, "synthetic-key");
	f->journal.magic = KR_RESET_MAGIC;
	f->disk = (struct kr_settings){.magic = KR_RECORD_MAGIC, .device_id = 4,
		.version = 99, .payload_size = 1, .payload = {1}};
	strcpy(f->disk.owner_generation, owner); strcpy(f->disk.sync_run, run);
	strcpy(f->disk.nonce, nonce); strcpy(f->disk.format, KR_DEV_PAYLOAD_FORMAT);
	uint8_t digest[32]; kr_hash(f->disk.payload, 1, digest); kr_hex(digest, 32, f->disk.hash);
	assert(kr_settings_valid(&f->disk));
	f->now = 1000;
	f->generation = 7;
	f->user_data = f->bonds = true;
	reboot(f);
}

static void sign(const uint8_t secret[64], const char *text, char out[87])
{
	uint8_t signature[64];
	crypto_ed25519_sign(signature, secret, (const uint8_t *)text, strlen(text));
	kr_base64_encode(signature, 64, out);
}

static void verify(const uint8_t public_key[32], const char *text, const char *encoded)
{
	uint8_t signature[64];
	assert(kr_base64_decode(encoded, signature, 64) == 64);
	assert(!crypto_ed25519_check(signature, public_key, (const uint8_t *)text, strlen(text)));
}

static void grant(struct fixture *f, const char *policy, char frame[KR_FRAME_MAX + 1])
{
	char signature[87], text[1024];
	assert(!challenge(f, signature));
	const struct kr_core *c = &f->core;
	const char *generation = c->current.magic ? c->current.owner_generation : owner;
	snprintf(text, sizeof(text), "TINGLE-KEYRING-RESET-AUTH\n%s\n%s\n%s\n%s\n4\n%s\n%s\n"
		"%" PRId64 "\n%" PRId64 "\n%s\n%s\n%s\n%s\n%s\n", KR_RESET_VERSION, server_id,
		c->identity.key_id, c->identity.serial, generation, run, c->reset.ownership_epoch,
		c->reset.ownership_epoch + 1, c->reset_challenge.id, c->reset_challenge.nonce,
		nonce, KR_RESET_SCOPE, policy);
	sign(f->server_secret, text, signature);
	snprintf(frame, KR_FRAME_MAX + 1, "{\"protocolVersion\":1,\"type\":\"RESET_DEVICE\",\"requestId\":\"r\","
		"\"resetProtocolVersion\":\"%s\",\"serverKeyId\":\"%s\",\"keyId\":\"%s\",\"deviceSerial\":\"%s\","
		"\"deviceId\":4,\"ownerGeneration\":\"%s\",\"resetRunId\":\"%s\",\"fromOwnershipEpoch\":%" PRId64 ","
		"\"toOwnershipEpoch\":%" PRId64 ",\"challengeId\":\"%s\",\"deviceNonce\":\"%s\",\"resetNonce\":\"%s\","
		"\"deletionScope\":\"%s\",\"recordPolicy\":\"%s\",\"serverSignature\":\"%s\"}", KR_RESET_VERSION,
		server_id, c->identity.key_id, c->identity.serial, generation, run,
		c->reset.ownership_epoch, c->reset.ownership_epoch + 1,
		c->reset_challenge.id, c->reset_challenge.nonce, nonce, KR_RESET_SCOPE, policy, signature);
}

static void request(struct fixture *f, const char *input, char out[KR_RESPONSE_MAX])
{
	char frame[KR_FRAME_MAX + 1]; strcpy(frame, input);
	struct kr_status status = {.battery_mv = -1, .charging = -1};
	kr_request(&f->core, frame, status, true, out);
	assert(strlen(out) < KR_RESPONSE_MAX - 1 && out[strlen(out) - 1] == '\n');
}

static void start(struct fixture *f, char frame[KR_FRAME_MAX + 1])
{
	grant(f, "REQUIRE_SYNCED", frame);
	char out[KR_RESPONSE_MAX]; request(f, frame, out);
	assert(strstr(out, "\"result\":\"PENDING\"") && !strstr(out, "signature"));
	assert(f->core.reset.phase == KR_RESETTING && f->core.stop_peer && f->blocked);
	assert(f->disk.magic && f->user_data && f->bonds && !f->erased);
}

static void query(struct fixture *f, char out[KR_RESPONSE_MAX])
{
	char frame[256];
	snprintf(frame, sizeof(frame), "{\"protocolVersion\":1,\"type\":\"GET_RESET_RESULT\","
		"\"requestId\":\"q\",\"resetRunId\":\"%s\",\"resetNonce\":\"%s\"}", run, nonce);
	request(f, frame, out);
}

static bool service_pending(void *ctx) { return ((struct fixture *)ctx)->core.reset.phase == KR_RESETTING; }
static uint32_t service_block(void *ctx)
{
	struct fixture *f = ctx; f->blocked = true; ++f->stop_calls; return ++f->generation;
}
static int service_apply(void *ctx, const struct kr_peer_plan *plan)
{
	struct fixture *f = ctx;
	if (f->stale_once) { ++f->generation; f->stale_once = false; }
	if (plan->generation != f->generation) { return -EAGAIN; }
	if (service_pending(f)) { assert(!plan->length && !plan->peer.valid && !plan->verified_time); }
	if (f->reset_on_apply) {
		f->reset_on_apply = false; char frame[KR_FRAME_MAX + 1]; start(f, frame);
	}
	return f->radio_failure;
}
static int service_phone(void *ctx) { return ((struct fixture *)ctx)->phone_failure; }
static const char *service_finish(void *ctx)
{ struct fixture *f = ctx; ++f->finish_calls; return kr_reset_resume(&f->core); }
static struct kr_service_io service_io(struct fixture *f)
{
	return (struct kr_service_io){.ctx = f, .reset_pending = service_pending, .block_reset = service_block,
		.apply = service_apply, .refresh_phone = service_phone, .finish_reset = service_finish};
}
static void service_loop_recovery(void)
{
	/* Use the same step called by main.c, the real signed RESET_DEVICE parser
	 * and durable reset core. Only radio/flash adapters are fake on the host. */
	for (unsigned recovery = 0; recovery < 2; ++recovery) {
		struct fixture f; init(&f); struct kr_identity id = f.core.identity;
		char frame[KR_FRAME_MAX + 1], out[KR_RESPONSE_MAX]; start(&f, frame);
		if (recovery) { reboot(&f); f.generation = 0; }
		struct kr_peer_plan plan = {.length = 32, .generation = 99, .verified_time = 123, .peer.valid = true}; bool update = true;
		struct kr_service_io io = service_io(&f); struct kr_service_progress p = kr_service_step(&plan, &update, &io);
		assert(p.reset_pending && p.applied && p.reset_attempted && !p.radio_error && !p.reset_error && !update);
		assert(plan.generation == f.generation && plan.generation && f.stop_calls == 1 && f.finish_calls == 1);
		query(&f, out); assert(strstr(out, "CLEARED") && f.core.reset.ownership_epoch == 1);
		assert(!f.disk.magic && !f.user_data && !f.bonds && !memcmp(&id, &f.core.identity, sizeof(id)));
		p = kr_service_step(&plan, &update, &io); assert(!p.reset_attempted && f.erased == 1 && f.finish_calls == 1);
	}
	for (unsigned failure = 0; failure < 3; ++failure) {
		struct fixture f; init(&f); char frame[KR_FRAME_MAX + 1]; start(&f, frame);
		f.radio_failure = failure == 0 ? -EIO : 0; f.phone_failure = failure == 1 ? -ENOMEM : 0; f.stale_once = failure == 2;
		struct kr_peer_plan plan = {0}; bool update = false; struct kr_service_io io = service_io(&f);
		struct kr_service_progress p = kr_service_step(&plan, &update, &io);
		assert(p.radio_error && update && !p.reset_attempted && !f.erased && !f.finish_calls && f.user_data);
		f.radio_failure = f.phone_failure = 0;
		p = kr_service_step(&plan, &update, &io);
		assert(p.applied && p.reset_attempted && !p.reset_error && f.core.reset.phase == KR_RESET_DONE);
	}
	struct fixture f; init(&f); f.reset_on_apply = true;
	struct kr_peer_plan plan = {.length = 32, .generation = f.generation}; bool update = true; struct kr_service_io io = service_io(&f);
	struct kr_service_progress p = kr_service_step(&plan, &update, &io);
	assert(p.applied && !p.reset_attempted && f.core.reset.phase == KR_RESETTING && !f.erased);
	p = kr_service_step(&plan, &update, &io); assert(p.reset_attempted && !p.reset_error && f.erased == 1);
	puts("PASS: main service stop generation, signed reset/reboot completion, failed stop retries and concurrent reset gate");
}

static void normal_and_retries(void)
{
	struct fixture f; init(&f);
	struct kr_identity identity = f.core.identity;
	char frame[KR_FRAME_MAX + 1], out[KR_RESPONSE_MAX]; start(&f, frame);
	char other[KR_FRAME_MAX + 1]; strcpy(other, frame);
	char *different = strstr(other, "\"resetRunId\":\""); assert(different);
	different[strlen("\"resetRunId\":\"")] = '3';
	request(&f, other, out); assert(strstr(out, "RESET_IN_PROGRESS"));
	request(&f, "{\"protocolVersion\":1,\"type\":\"GET_RESET_RESULT\",\"requestId\":\"q\","
		"\"resetRunId\":\"00000000-0000-4000-8000-000000000000\",\"resetNonce\":\"bad\"}", out);
	assert(strstr(out, "RESET_NOT_FOUND"));
	char proof[87]; struct kr_settings next = f.disk;
	assert(!strcmp(kr_store(&f.core, &next, NULL, proof), "RESET_IN_PROGRESS"));
	assert(!kr_reset_resume(&f.core));
	assert(!f.user_data && !f.disk.magic && !f.bonds && f.erased == 1);
	assert(f.core.reset.phase == KR_RESET_DONE && f.core.reset.ownership_epoch == 1);
	assert(kr_reset_record_valid(&f.core) && !memcmp(&identity, &f.core.identity, sizeof(identity)));
	strcpy(proof, f.core.reset.signature);
	reboot(&f); query(&f, out);
	assert(strstr(out, "CLEARED") && strstr(out, proof) && strstr(out, "LOGICAL"));
	request(&f, frame, out); assert(strstr(out, proof) && f.erased == 1);
	/* A new owner binding must survive both old command replay and old result reads. */
	f.disk = next; f.disk.version = 1; strcpy(f.disk.owner_generation, next_owner); f.core.current = f.disk;
	reboot(&f); request(&f, frame, out); query(&f, out);
	assert(strstr(out, proof) && f.erased == 1 && f.disk.version == 1 && f.core.current.magic);
	assert(!memcmp(&identity, &f.core.identity, sizeof(identity)));
	f.core.reset.signature[0] ^= 1; assert(!kr_reset_record_valid(&f.core));
	f.core.reset = f.journal; ++f.core.reset.ownership_epoch; assert(!kr_reset_record_valid(&f.core));
}

static void power_cuts(void)
{
	char frame[KR_FRAME_MAX + 1], out[KR_RESPONSE_MAX];
	for (int committed = 1; committed <= 2; ++committed) {
		struct fixture f; init(&f); f.fail_intent = committed;
		grant(&f, "REQUIRE_SYNCED", frame); request(&f, frame, out);
		assert(strstr(out, "STORAGE_ERROR") && f.core.storage_error && !f.erased && f.disk.magic);
		reboot(&f); f.fail_intent = 0;
		if (committed == 1) { assert(f.core.reset.phase == KR_RESET_NONE && f.core.current.version == 99); }
		else { assert(f.core.reset.phase == KR_RESETTING); assert(!kr_reset_resume(&f.core)); }
	}
	for (int step = 1; step <= 2; ++step) {
		struct fixture f; init(&f); start(&f, frame); f.fail_erase = step;
		assert(kr_reset_resume(&f.core)); query(&f, out);
		assert(strstr(out, "PENDING") && !strstr(out, "CLEARED") && !f.journal.signature[0]);
		reboot(&f); f.fail_erase = 0;
		assert(!f.core.current.magic && !kr_reset_resume(&f.core)); query(&f, out);
		assert(strstr(out, "CLEARED") && f.journal.ownership_epoch == 1);
	}
	for (int committed = 1; committed <= 2; ++committed) {
		struct fixture f; init(&f); start(&f, frame); f.fail_final = committed;
		assert(!strcmp(kr_reset_resume(&f.core), "STORAGE_ERROR"));
		query(&f, out); assert(strstr(out, "PENDING") && !strstr(out, "CLEARED"));
		assert(!f.disk.magic && !f.user_data && !f.bonds);
		reboot(&f); f.fail_final = 0;
		assert(!kr_reset_resume(&f.core)); query(&f, out); assert(strstr(out, "CLEARED"));
		assert(f.journal.ownership_epoch == 1 && f.erased == (committed == 1 ? 2u : 1u));
	}
}

static void refusals(void)
{
	struct fixture f;
	char frame[KR_FRAME_MAX + 1], out[KR_RESPONSE_MAX];
	const char *fields[] = {"\"serverSignature\":\"", "\"deviceSerial\":\"", "\"keyId\":\"",
		"\"ownerGeneration\":\"", "\"deviceNonce\":\"", "\"resetNonce\":\"", "\"serverKeyId\":\"",
		"\"deviceId\":", "\"fromOwnershipEpoch\":", "\"toOwnershipEpoch\":"};
	for (size_t i = 0; i < sizeof(fields) / sizeof(fields[0]); ++i) {
		init(&f); grant(&f, "REQUIRE_SYNCED", frame);
		char *p = strstr(frame, fields[i]); assert(p); p += strlen(fields[i]);
		*p = *p == 'B' ? 'C' : 'B'; request(&f, frame, out);
		assert(strstr(out, "\"type\":\"ERROR\"") && !f.erased && f.journal.phase == KR_RESET_NONE);
	}
	init(&f); grant(&f, "REQUIRE_SYNCED", frame); f.now += 60000;
	request(&f, frame, out); assert(strstr(out, "RESET_CHALLENGE_REQUIRED"));
	init(&f); grant(&f, "REQUIRE_SYNCED", frame); reboot(&f);
	request(&f, frame, out); assert(strstr(out, "RESET_CHALLENGE_REQUIRED"));
	init(&f); f.pending = 2; grant(&f, "REQUIRE_SYNCED", frame);
	request(&f, frame, out); assert(strstr(out, "PENDING_RECORDS") && f.disk.magic);
	grant(&f, "DISCARD_WITH_CONSENT", frame); f.pending = -1;
	request(&f, frame, out); assert(strstr(out, "RECORD_STATE_UNKNOWN") && f.disk.magic);
	f.pending = 2; request(&f, frame, out); assert(strstr(out, "PENDING"));
	assert(!kr_reset_resume(&f.core) && f.core.reset.policy == KR_RESET_DISCARD);
	query(&f, out); assert(strstr(out, "DISCARD_WITH_CONSENT"));
	init(&f); f.core.reset.ownership_epoch = INT64_MAX;
	struct kr_json json; strcpy(frame, "{\"resetRunId\":\"bad\"}"); assert(kr_json_parse(frame, &json));
	assert(kr_reset_begin(&f.core, &json, f.now, server_id, f.server_public));
	int64_t n; assert(kr_nonnegative("0", &n) && n == 0);
	assert(!kr_nonnegative("00", &n) && !kr_nonnegative("-1", &n));
}

static void sign_settings(struct fixture *f, const struct kr_settings *s,
	struct kr_settings_auth *auth, char encoded[87])
{
	char text[768];
	snprintf(text, sizeof(text), "TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n4\n%s\n%s\n"
		"%" PRId64 "\n%s\n%s\n%s\n%s\n1793577900\n%" PRId64 "\n", KR_SETTINGS_PROOF_VERSION_2,
		server_id, f->core.identity.serial, f->core.identity.key_id, s->owner_generation,
		s->sync_run, s->version, s->format, s->hash, s->nonce, auth->device_nonce, auth->ownership_epoch);
	sign(f->server_secret, text, encoded); auth->signature = encoded;
}

static void v2_binding_and_vectors(const char *path)
{
	struct fixture f; init(&f);
	struct kr_settings s = f.disk;
	char frame[KR_FRAME_MAX + 1], out[KR_RESPONSE_MAX], proof[87], registration[87], encoded[87];
	start(&f, frame); assert(!kr_reset_resume(&f.core));
	char grant_frame[KR_FRAME_MAX + 1]; strcpy(grant_frame, frame);
	struct kr_json original_grant; assert(kr_json_parse(grant_frame, &original_grant));
	assert(!strcmp(kr_store(&f.core, &s, NULL, proof), "PROTOCOL_UPGRADE_REQUIRED"));
	struct kr_settings_auth auth = {.version2 = true, .ownership_epoch = 0,
		.protocol_version = KR_SETTINGS_PROOF_VERSION_2, .server_key_id = server_id,
		.server_time = "2026-11-02T00:05:00Z"};
	assert(!strcmp(kr_store(&f.core, &s, &auth, proof), "OWNERSHIP_EPOCH_MISMATCH"));
	auth.ownership_epoch = 1;
	assert(!strcmp(kr_store(&f.core, &s, &auth, proof), "OWNER_MISMATCH"));
	strcpy(s.owner_generation, next_owner); s.version = 1;
	uint8_t bytes[32]; memset(bytes, 7, sizeof(bytes));
	kr_issue_settings_nonce(&f.sync, bytes, f.now); auth.device_nonce = f.sync.value;
	sign_settings(&f, &s, &auth, encoded);
	auth.ownership_epoch = 2;
	int64_t time;
	assert(!strcmp(kr_verify_dev_settings(&f.core.identity, &s, &auth, &f.sync, f.now,
		server_id, f.server_public, &time), "INVALID_SERVER_PROOF"));
	auth.ownership_epoch = 1;
	snprintf(frame, sizeof(frame), "{\"protocolVersion\":1,\"type\":\"GET_REGISTRATION_PROOF\",\"requestId\":\"r\","
		"\"proofProtocolVersion\":\"%s\",\"challengeId\":\"%s\",\"nonce\":\"%s\",\"expiresAt\":\"2026-11-02T00:05:00Z\"}",
		KR_PROOF_VERSION, run, nonce);
	request(&f, frame, out); assert(strstr(out, "PROTOCOL_UPGRADE_REQUIRED"));
	snprintf(frame, sizeof(frame), "{\"protocolVersion\":1,\"type\":\"GET_REGISTRATION_PROOF\",\"requestId\":\"r\","
		"\"proofProtocolVersion\":\"%s\",\"challengeId\":\"%s\",\"nonce\":\"%s\",\"expiresAt\":\"2026-11-02T00:05:00Z\"}",
		KR_PROOF_VERSION_2, run, nonce);
	request(&f, frame, out); assert(strstr(out, "UNOWNED") && strstr(out, "\"ownershipEpoch\":1"));
	assert(!kr_registration_proof_v2(&f.core.identity, run, nonce, 1793577900, 1, registration));
	assert(strstr(out, registration));
	char settings_frame[KR_FRAME_MAX + 1];
	snprintf(settings_frame, sizeof(settings_frame), "{\"protocolVersion\":1,\"type\":\"SYNC_SETTINGS\",\"requestId\":\"s\","
		"\"proofProtocolVersion\":\"%s\",\"ownershipEpoch\":1,\"deviceId\":4,\"settingsVersion\":1,"
		"\"ownerGeneration\":\"%s\",\"syncRunId\":\"%s\",\"nonce\":\"%s\",\"payloadHash\":\"%s\","
		"\"payloadFormat\":\"%s\",\"payloadEncoding\":\"base64url\",\"payload\":\"AQ\",\"keyId\":\"synthetic-key\","
		"\"serverProofProtocolVersion\":\"%s\",\"serverKeyId\":\"%s\",\"deviceNonce\":\"%s\","
		"\"serverTime\":\"%s\",\"serverSignature\":\"%s\"}", KR_PROOF_VERSION_2, next_owner, run, nonce,
		s.hash, s.format, auth.protocol_version, server_id, auth.device_nonce, auth.server_time, auth.signature);
	request(&f, settings_frame, out); assert(strstr(out, "\"stored\":true") && f.disk.version == 1);
	char *stored_signature = strstr(out, "\"signature\":\""); assert(stored_signature);
	memcpy(proof, stored_signature + strlen("\"signature\":\""), 86); proof[86] = 0;
	request(&f, frame, out); assert(strstr(out, "OWNER_MISMATCH"));
	/* Write only public synthetic vectors for independent JDK verification. */
	FILE *file = fopen(path, "wb"); assert(file);
	char public_key[44], challenge_sig[87], text[1024];
	kr_base64_encode(f.core.identity.public_key, 32, public_key);
	f.now = 2000; assert(!challenge(&f, challenge_sig));
	snprintf(text, sizeof(text), "TINGLE-KEYRING-RESET-CHALLENGE\n%s\nsynthetic-key\nsynthetic-device\n1\n%s\n%s\n%s\n60\n",
		KR_RESET_VERSION, next_owner, f.core.reset_challenge.id, f.core.reset_challenge.nonce);
	verify(f.core.identity.public_key, text, challenge_sig);
	fprintf(file, "publicKey=%s\ncleared=%s\nregistration=%s\nstored=%s\nhash=%s\nchallenge=%s\nchallengeId=%s\ndeviceNonce=%s\n",
		public_key, f.core.reset.signature, registration, proof, s.hash, challenge_sig,
		f.core.reset_challenge.id, f.core.reset_challenge.nonce);
	kr_base64_encode(f.server_public, 32, public_key);
	fprintf(file, "serverPublicKey=%s\nauthorization=%s\nauthChallengeId=%s\nauthDeviceNonce=%s\n", public_key,
		kr_json_string(&original_grant, "serverSignature"), kr_json_string(&original_grant, "challengeId"),
		kr_json_string(&original_grant, "deviceNonce"));
	assert(!fclose(file));
}

static void challenge_and_bounds(void)
{
	struct fixture f; init(&f);
	char a[87], b[87], frame[KR_FRAME_MAX + 1], out[KR_RESPONSE_MAX];
	assert(!challenge(&f, a)); f.now++;
	assert(!challenge(&f, b) && !strcmp(a, b)); f.now += 60000;
	assert(!challenge(&f, b) && strcmp(a, b));
	memset(f.core.identity.serial, 'x', 64); f.core.identity.serial[64] = 0;
	memset(f.core.identity.key_id, 'k', 64); f.core.identity.key_id[64] = 0;
	f.core.current.device_id = INT64_MAX;
	f.core.reset.ownership_epoch = INT64_MAX - 1;
	/* A valid maximum-epoch grant must not overflow the canonical transcript. */
	grant(&f, "REQUIRE_SYNCED", frame);
	/* Align the fixture's deliberately fixed deviceId with the command. */
	f.core.current.device_id = 4;
	request(&f, frame, out); assert(strstr(out, "PENDING"));
	assert(!kr_reset_resume(&f.core));
	char query_frame[512], request_id[37]; memset(request_id, '\x01', 36); request_id[36] = 0;
	char quoted[224]; assert(kr_json_quote(request_id, quoted, sizeof(quoted)));
	snprintf(query_frame, sizeof(query_frame), "{\"protocolVersion\":1,\"type\":\"GET_RESET_RESULT\","
		"\"requestId\":%s,\"resetRunId\":\"%s\",\"resetNonce\":\"%s\"}", quoted, run, nonce);
	request(&f, query_frame, out); assert(strstr(out, "CLEARED") && strlen(out) < KR_RESPONSE_MAX);
	assert(f.core.reset.ownership_epoch == INT64_MAX);
	char *from = strstr(frame, "\"fromOwnershipEpoch\":9223372036854775806"); assert(from);
	from[strlen("\"fromOwnershipEpoch\":922337203685477580")] = '7';
	char *old_run = strstr(frame, "\"resetRunId\":\""); assert(old_run);
	old_run[strlen("\"resetRunId\":\"")] = '3';
	request(&f, frame, out); assert(strstr(out, "OWNERSHIP_EPOCH_MISMATCH"));
}

int main(int argc, char **argv)
{
	assert(argc == 2);
	normal_and_retries(); power_cuts(); refusals(); challenge_and_bounds(); v2_binding_and_vectors(argv[1]); service_loop_recovery();
	puts("PASS: signed reset, key preservation, power cuts, replay/new owner, record gates, epoch v2, maximum frames");
	return 0;
}

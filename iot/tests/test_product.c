#include "product.h"
#include "matching.h"
#include "battery.h"
#include "feedback.h"
#include "service.h"
#include "storage.h"
#include "settings_storage.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Synthetic keys and fake durable storage exist only in this host executable. */
struct test_board {
	struct kr_core core;
	struct kr_engine engine;
	struct kr_product app;
	struct kr_peer_runtime radio;
	uint8_t blobs[1024][KR_EVIDENCE_BYTES];
	size_t lengths[1024];
	struct kr_record saved[KR_RECORDS];
	struct kr_suppression suppressed[KR_SUPPRESSIONS];
	int64_t ms;
	unsigned random, writes, stops;
	int fail_key;
	bool fail_raw;
	uint32_t persisted_counter;
	bool fail_counter;
	char settings_signature[87];
	struct kr_settings persisted;
	uint8_t sync_nonce[32];
};
static struct test_board boards[4];
static uint8_t server[32], server_secret[64], profile[196];
static char server_id[29] = "dev-ed25519-";
static char response[KR_RESPONSE_MAX], command[KR_FRAME_MAX + 1];
static const int64_t epoch = 1793577900;
static int64_t uptime_ms(void *ctx) { return ((struct test_board *)ctx)->ms; }
static void random_bytes(void *ctx, uint8_t *out, size_t n)
{
	struct test_board *b = ctx;
	for (size_t i = 0; i < n; ++i) { b->random = b->random * 1664525 + 1013904223; out[i] = (uint8_t)(b->random >> 24); }
}
static int random_result(void *ctx, uint8_t *out, size_t n) { random_bytes(ctx, out, n); return 0; }
static int blob(void *ctx, uint16_t key, void *data, size_t n, bool write)
{
	struct test_board *b = ctx; assert(key < 1024 && n <= KR_EVIDENCE_BYTES);
	if ((int)key == b->fail_key) { return -EIO; }
	if (!write) { if (b->lengths[key] != n) { return -ENOENT; } memcpy(data, b->blobs[key], n); return 0; }
	if (n) { memcpy(b->blobs[key], data, n); } b->lengths[key] = n; ++b->writes; return 0;
}
static int space(void *ctx, int64_t *total, int64_t *used) { (void)ctx; *total = 262144; *used = 12000; return 0; }
static void stop(void *ctx) { ++((struct test_board *)ctx)->stops; }
static int config(void *ctx, const struct kr_config *v) { (void)ctx; (void)v; return 0; }
static int save_settings(void *ctx, const struct kr_settings *s)
{ ((struct test_board *)ctx)->persisted = *s; return 0; }
static const char *authorize_settings(void *ctx, const struct kr_settings *s, const struct kr_settings_auth *a, struct kr_peer_plan *plan)
{
	struct test_board *b = ctx; struct kr_settings_nonce nonce; uint8_t seed[32] = {0};
	kr_issue_settings_nonce(&nonce, b->sync_nonce, b->ms);
	const char *failure = kr_verify_dev_settings(&b->core.identity, s, a, &nonce, b->ms, server_id, server, &plan->verified_time);
	if (failure) { return failure; }
	struct kr_policy policy;
	if (!kr_policy_decode(s->payload, s->payload_size, &policy)) { return "INVALID_SETTINGS"; }
	if (!(policy.profile[195] & 1u) || !policy.complete || !policy.enabled) { return NULL; }
	random_bytes(b, seed, 32);
	if (!kr_peer_material_make(&plan->peer, &b->core.identity, s, a, seed)) { return "INVALID_PEER_GRANT"; }
	plan->length = 16; return NULL;
}
static int raw_record(void *ctx, unsigned slot, const struct kr_record *r)
{ struct test_board *b = ctx; if (b->fail_raw) { return -EIO; } b->saved[slot] = *r; return 0; }
static int record(void *ctx, unsigned slot, const struct kr_record *r) { return kr_product_record(&((struct test_board *)ctx)->app, slot, r); }
static int suppression(void *ctx, unsigned slot, const struct kr_suppression *s) { ((struct test_board *)ctx)->suppressed[slot] = *s; return 0; }
static int wrapped_suppression(void *ctx, unsigned slot, const struct kr_suppression *s)
{ return kr_product_suppression(&((struct test_board *)ctx)->app, slot, s); }
static bool same_peer(void *ctx, unsigned slot, uint64_t peer)
{ return kr_product_same_peer(&((struct test_board *)ctx)->app, slot, peer); }
static bool candidate_ready(void *ctx, unsigned slot)
{ return kr_product_candidate_ready(&((struct test_board *)ctx)->app, slot); }
static uint64_t error_key(void *ctx, uint64_t peer, const uint8_t *raw, size_t n)
{ return kr_product_error_key(&((struct test_board *)ctx)->app, peer, raw, n); }
static int sequence_save(void *ctx, uint32_t next)
{ struct test_board *b = ctx; if (b->fail_counter) { return -EIO; } b->persisted_counter = next; return 0; }
static bool application(void *ctx, const struct kr_json *j, const char *request, struct kr_status status, char *out)
{ return kr_product_dispatch(&((struct test_board *)ctx)->app, j, request, status, out); }
static void sign(const void *msg, size_t n, char out[87])
{ uint8_t bytes[64]; crypto_ed25519_sign(bytes, server_secret, msg, n); kr_base64_encode(bytes, 64, out); }
static void vector(FILE *f, const char *key, const void *bytes, size_t n)
{ char text[4097]; assert(n <= 2048); kr_hex(bytes, n, text); fprintf(f, "%s=%s\n", key, text); }
static void request(struct test_board *b, const char *json, const char *expect)
{
	assert(strlen(json) + 45 <= KR_FRAME_MAX);
	snprintf(command, sizeof(command), "{\"protocolVersion\":1,\"requestId\":\"test\",%s", json + 1);
	kr_request(&b->core, command, (struct kr_status){.battery_mv = 3750, .charging = -1}, true, response);
	assert(strlen(response) && response[strlen(response) - 1] == '\n');
	if (!strstr(response, expect)) { fprintf(stderr, "expected %s got %s\n", expect, response); abort(); }
}
static void field(const char *key, char *out)
{
	char copy[KR_RESPONSE_MAX]; strcpy(copy, response); struct kr_json j; assert(kr_json_parse(copy, &j));
	const char *s = kr_json_string(&j, key); assert(s); strcpy(out, s);
}
static int64_t number(const char *key)
{
	char copy[KR_RESPONSE_MAX]; strcpy(copy, response); struct kr_json j; assert(kr_json_parse(copy, &j));
	int64_t value; assert(kr_nonnegative(kr_json_number(&j, key), &value)); return value;
}
static void make_settings(struct test_board *b, unsigned index, unsigned account, struct kr_peer_material *material, FILE *vectors)
{
	struct kr_settings *s = &b->core.current;
	uint8_t seed[32]; memset(seed, (int)(index + 1), sizeof(seed));
	crypto_ed25519_key_pair(b->core.identity.secret, b->core.identity.public_key, seed);
	uint8_t hardware[8] = {0}; hardware[0] = (uint8_t)(index + 1);
	strcpy(b->core.identity.serial, "XIAO-"); kr_hex(hardware, 8, b->core.identity.serial + 5);
	strcpy(b->core.identity.key_id, "ed25519-"); kr_hex(b->core.identity.public_key, 8, b->core.identity.key_id + 8);
	s->magic = KR_RECORD_MAGIC; s->version = 10; s->device_id = 100 + index; s->payload_size = KR_POLICY_BASE;
	strcpy(s->format, KR_POLICY_FORMAT); snprintf(s->owner_generation, sizeof(s->owner_generation), "%08x-3333-4333-8333-333333333333", index + 1);
	strcpy(s->sync_run, "22222222-2222-4222-8222-222222222222"); strcpy(s->nonce, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	uint8_t *v = s->payload; memcpy(v, "TSV3", 4); memcpy(v + 4, profile, 196);
	v[182] = index % 2 ? 2 : 1; v[183] = index % 2 ? 1 : 2;
	uint8_t *g = v + 200; g[0] = 4; memcpy(g + 1, hardware, 8); kr_put64(g + 9, (uint64_t)s->device_id);
	kr_put32(g + 17, 10); kr_put32(g + 21, 1); kr_put32(g + 25, 3 * 86400); g[29 + 3] = (uint8_t)(index + 1);
	memset(g + 29 + 4, 0x33, 12); g[29 + 6] = 0x43; g[29 + 8] = 0x83;
	memset(g + 45, 0x22, 16); g[45 + 6] = 0x42; g[45 + 8] = 0x82;
	memset(g + 93, (int)account, 16); kr_put32(g + 109, KR_MATCH_POLICY); kr_put32(g + 113, KR_MATCH_RULE);
	kr_put64(g + 117, (uint64_t)epoch + 14 * 86400); kr_put64(g + 125, (uint64_t)epoch);
	kr_hash(v + 4, 196, g + 133); memcpy(g + 165, b->core.identity.public_key, 32);
	uint8_t message[224]; static const char domain[] = "TINGLE-PEER-GRANT-V3\n";
	memcpy(message, domain, sizeof(domain) - 1); memcpy(message + sizeof(domain) - 1, g, 197);
	crypto_ed25519_sign(g + 197, server_secret, message, sizeof(domain) - 1 + 197);
	kr_put32(v + 461, KR_MATCH_POLICY); kr_put32(v + 465, KR_MATCH_RULE); kr_put64(v + 469, (uint64_t)epoch + 14 * 86400);
	kr_put16(v + 479, 5); v[481] = v[482] = 1;
	kr_put32(v + 483, 3 * 86400);
	uint8_t digest[32]; kr_hash(v, s->payload_size, digest); kr_hex(digest, 32, s->hash);
	char signature[87], text[768]; int n = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\n1\n",
		KR_SETTINGS_PROOF_VERSION_2, server_id, b->core.identity.serial, b->core.identity.key_id,
		s->device_id, s->owner_generation, s->sync_run, s->version, s->format, s->hash, s->nonce, s->nonce, epoch);
	sign(text, (size_t)n, signature);
	strcpy(b->settings_signature, signature);
	struct kr_settings_auth auth = {.version2 = true, .ownership_epoch = 1, .protocol_version = KR_SETTINGS_PROOF_VERSION_2,
		.server_key_id = server_id, .signature = signature, .device_nonce = s->nonce, .server_time = "2026-11-02T00:05:00Z"};
	struct kr_settings_nonce nonce; uint8_t zero[32] = {0}; kr_issue_settings_nonce(&nonce, zero, 0); int64_t verified = 0;
	assert(kr_settings_valid(s));
	assert(!kr_verify_dev_settings(&b->core.identity, s, &auth, &nonce, 1, server_id, server, &verified) && verified == epoch);
	uint8_t grant_backup[261]; memcpy(grant_backup, g, 261); g[165] ^= 1;
	assert(!kr_peer_grant(g, server, (uint64_t)epoch)); memcpy(g, grant_backup, 261);
	memset(seed, (int)(index + 40), 32); assert(kr_peer_material_make(material, &b->core.identity, s, &auth, seed));
	if (!index) { vector(vectors, "grant", g, 261); vector(vectors, "bundle", v, s->payload_size); vector(vectors, "settingsTranscript", text, (size_t)n);
		uint8_t sig[64]; assert(kr_base64_decode(signature, sig, 64) == 64); vector(vectors, "settingsSignature", sig, 64); }
}
static void boot(unsigned index, unsigned account, FILE *vectors)
{
	struct test_board *b = &boards[index]; memset(b, 0, sizeof(*b)); b->random = index + 1; b->fail_key = -1;
	struct kr_peer_material material; make_settings(b, index, account, &material, vectors);
	b->core.reset.ownership_epoch = 1; b->core.connection_epoch = 1; b->core.profile_transport_protected = true;
	b->core.platform.ctx = b; b->core.platform.application = application;
	b->core.platform.authorize = authorize_settings; b->core.platform.save = save_settings;
	struct kr_settings settings = b->core.current; memset(&b->core.current, 0, sizeof(b->core.current));
	char encoded[2700], json[KR_FRAME_MAX]; kr_base64_encode(settings.payload, settings.payload_size, encoded);
	snprintf(json, sizeof(json), "{\"type\":\"SYNC_SETTINGS\",\"proofProtocolVersion\":\"%s\",\"ownershipEpoch\":1,\"keyId\":\"%s\",\"deviceId\":%" PRId64 ","
		"\"settingsVersion\":10,\"ownerGeneration\":\"%s\",\"syncRunId\":\"%s\",\"nonce\":\"%s\",\"payloadHash\":\"%s\",\"payloadFormat\":\"%s\","
		"\"payloadEncoding\":\"base64url\",\"payload\":\"%s\",\"serverProofProtocolVersion\":\"%s\",\"serverKeyId\":\"%s\",\"deviceNonce\":\"%s\","
		"\"serverTime\":\"2026-11-02T00:05:00Z\",\"serverSignature\":\"%s\"}", KR_PROOF_VERSION_2, b->core.identity.key_id, settings.device_id,
		settings.owner_generation, settings.sync_run, settings.nonce, settings.hash, settings.format, encoded,
		KR_SETTINGS_PROOF_VERSION_2, server_id, settings.nonce, b->settings_signature);
	/* Before application attachment this exercises the real common SYNC
	 * parser, server validator, persistent commit and signed STORED response. */
	b->core.platform.application = NULL; request(b, json, "\"stored\":true");
	assert(!memcmp(&b->persisted, &settings, sizeof(settings)));
	struct kr_peer_plan plan; assert(kr_peer_after_response(&b->core, true, &plan) && plan.peer.valid);
	material = plan.peer;
	b->core.platform.application = application;
	struct kr_io io = {.ctx = b, .config = config, .record = raw_record, .suppression = suppression, .random = random_bytes, .sequence = sequence_save};
	kr_init(&b->engine, material.device, io);
	struct kr_product_io app_io = {.ctx = b, .uptime_ms = uptime_ms, .random = random_result, .blob = blob, .space = space, .stop_feedback = stop};
	kr_product_init(&b->app, &b->core, &b->engine, app_io, server); b->engine.io.record = record; b->engine.io.suppression = wrapped_suppression;
	b->engine.io.same_peer = same_peer; b->engine.io.candidate_ready = candidate_ready;
	b->engine.io.error_key = error_key;
	assert(kr_product_apply(&b->app, &b->radio, epoch)); assert(!kr_peer_start(&b->radio, &material, server, &b->engine));
	kr_product_bind_peer(&b->app, &b->radio); assert(kr_product_ready(&b->app));
}
static void owner(struct test_board *b, FILE *vectors)
{
	request(b, "{\"type\":\"GET_OWNER_CHALLENGE\"}", "OWNER_CHALLENGE"); char nonce[44]; field("deviceNonce", nonce);
	char text[1200], signature[87], json[1800]; int n = snprintf(text, sizeof(text),
		"TINGLE-OWNER-SESSION\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n1\n%s\n15\n%" PRId64 "\n%" PRId64 "\n",
		KR_OWNER_PROOF, server_id, b->core.identity.serial, b->core.identity.key_id, b->core.current.device_id,
		b->core.current.owner_generation, nonce, epoch, epoch + 300); sign(text, (size_t)n, signature);
	snprintf(json, sizeof(json), "{\"type\":\"AUTHORIZE_OWNER\",\"proofProtocolVersion\":\"%s\",\"serverKeyId\":\"%s\",\"deviceId\":%" PRId64 ","
		"\"ownerGeneration\":\"%s\",\"ownershipEpoch\":1,\"deviceNonce\":\"%s\",\"permissions\":15,\"serverTime\":\"2026-11-02T00:05:00Z\","
		"\"expiresAtEpochSeconds\":%" PRId64 ",\"serverSignature\":\"%s\"}", KR_OWNER_PROOF, server_id, b->core.current.device_id,
		b->core.current.owner_generation, nonce, epoch + 300, signature);
	request(b, json, "OWNER_RESULT"); int64_t deadline = b->app.session_deadline; request(b, json, "OWNER_RESULT"); assert(deadline == b->app.session_deadline);
	if (vectors) { vector(vectors, "ownerTranscript", text, (size_t)n); uint8_t sig[64]; assert(kr_base64_decode(signature, sig, 64) == 64); vector(vectors, "ownerSignature", sig, 64); }
}
static void test_public_name(struct test_board *b)
{
	const char *get = "{\"type\":\"GET_PUBLIC_NAME\"}";
	const char *set = "{\"type\":\"SET_PUBLIC_NAME\",\"name\":\"\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00AB\"}";
	const char *clear = "{\"type\":\"CLEAR_PUBLIC_NAME\"}";
	assert(!*kr_product_public_name(&b->app));
	request(b, set, "OWNER_SESSION_REQUIRED");
	b->core.profile_transport_protected = false;
	request(b, set, "SECURE_TRANSPORT_REQUIRED");
	b->core.profile_transport_protected = true;
	owner(b, NULL);
	b->app.permissions = KR_OWNER_READ;
	request(b, set, "OWNER_SESSION_REQUIRED");
	b->app.permissions = KR_OWNER_CONTROL;
	request(b, get, "\"enabled\":false,\"name\":null");
	request(b, "{\"type\":\"SET_PUBLIC_NAME\",\"name\":\" \\n\"}", "INVALID_PUBLIC_NAME");
	request(b, "{\"type\":\"SET_PUBLIC_NAME\",\"name\":\"\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\\uac00\"}", "PUBLIC_NAME_TOO_LONG");
	request(b, "{\"type\":\"SET_PUBLIC_NAME\",\"name\":\"\xc0\xaf\"}", "INVALID_MESSAGE");
	request(b, "{\"type\":\"SET_PUBLIC_NAME\",\"name\":\"a\\\"b\"}", "\"name\":\"a\\\"b\"");
	request(b, set, "\"enabled\":true");
	assert(strlen(kr_product_public_name(&b->app)) == KR_PUBLIC_NAME_MAX);

	struct kr_product restored;
	kr_product_init(&restored, &b->core, &b->engine, b->app.io, server);
	assert(!strcmp(kr_product_public_name(&restored), kr_product_public_name(&b->app)));
	++b->core.reset.ownership_epoch;
	assert(!*kr_product_public_name(&restored));
	request(b, clear, "OWNER_SESSION_REQUIRED");
	--b->core.reset.ownership_epoch;
	char generation = b->core.current.owner_generation[0];
	b->core.current.owner_generation[0] = 'f';
	assert(!*kr_product_public_name(&restored));
	b->core.current.owner_generation[0] = generation;
	b->core.reset.phase = KR_RESETTING;
	assert(!*kr_product_public_name(&restored));
	request(b, get, "OWNER_NOT_BOUND");
	b->core.reset.phase = 0;
	kr_product_forget_owner(&restored);
	assert(!*kr_product_public_name(&restored));

	b->fail_key = KR_PUBLIC_NAME_STORAGE_KEY;
	request(b, clear, "STORAGE_ERROR");
	assert(!*kr_product_public_name(&b->app));
	request(b, get, "STORAGE_ERROR");
	b->fail_key = -1;
	++b->core.connection_epoch;
	request(b, clear, "OWNER_SESSION_REQUIRED");
	owner(b, NULL);
	request(b, clear, "\"enabled\":false,\"name\":null");
	request(b, clear, "\"enabled\":false,\"name\":null");
	assert(!b->lengths[KR_PUBLIC_NAME_STORAGE_KEY]);
	kr_product_init(&restored, &b->core, &b->engine, b->app.io, server);
	assert(!*kr_product_public_name(&restored));
	puts("public name: owner scope, UTF-8 limits, persistence, reset blocking and erase retry passed");
}

static void exchange_pair(unsigned rounds)
{
	uint8_t packet[512]; int sent;
	for (unsigned turn = 0; turn < rounds; ++turn) {
		for (unsigned i = 0; i < 2; ++i) {
			size_t n = kr_peer_advertisement(&boards[i].radio, &boards[i].engine, packet, &sent); assert(n);
			(void)kr_peer_receive(&boards[1 - i].radio, &boards[1 - i].engine, packet, n, (uint64_t)epoch);
			kr_sent(&boards[i].engine, sent); kr_drain(&boards[i].engine);
		}
	}
}
static void restart_storage(struct test_board *b, struct kr_peer_material *material)
{
	struct kr_io io = b->engine.io; io.record = raw_record; io.suppression = suppression;
	struct kr_config config_copy = b->engine.config; uint8_t cursor[16]; memcpy(cursor, b->engine.cursor_epoch, 16);
	struct kr_product_io app_io = b->app.io;
	kr_init(&b->engine, config_copy.device, io); b->engine.config = config_copy; b->engine.next_sequence = b->persisted_counter;
	memcpy(b->engine.cursor_epoch, cursor, 16); memcpy(b->engine.records, b->saved, sizeof(b->saved)); memcpy(b->engine.suppressed, b->suppressed, sizeof(b->suppressed));
	assert(kr_restored_valid(&b->engine));
	kr_product_init(&b->app, &b->core, &b->engine, app_io, server); b->engine.io.record = record; b->engine.io.suppression = wrapped_suppression;
	b->engine.io.same_peer = same_peer; b->engine.io.candidate_ready = candidate_ready;
	b->engine.io.error_key = error_key;
	assert(kr_product_apply(&b->app, &b->radio, epoch)); assert(!kr_peer_start(&b->radio, material, server, &b->engine));
	kr_product_bind_peer(&b->app, &b->radio); assert(kr_product_ready(&b->app));
}
static void rotate_owner(unsigned index, unsigned account, unsigned generation, FILE *scratch)
{
	struct test_board *b = &boards[index]; struct kr_identity original = b->core.identity;
	memset(&b->core.current, 0, sizeof(b->core.current));
	struct kr_peer_material material; make_settings(b, index, account, &material, scratch);
	assert(!memcmp(&original, &b->core.identity, sizeof(original)));
	struct kr_settings *s = &b->core.current; uint8_t *g = s->payload + 200;
	uint32_t number = (generation << 16) | account;
	snprintf(s->owner_generation, sizeof(s->owner_generation), "%08x-3333-4333-8333-333333333333", number);
	for (unsigned i = 0; i < 4; ++i) { g[29 + i] = (uint8_t)(number >> (24 - 8 * i)); }
	kr_put32(g + 21, generation);
	uint8_t message[224]; const char *domain = "TINGLE-PEER-GRANT-V3\n";
	memcpy(message, domain, strlen(domain)); memcpy(message + strlen(domain), g, 197);
	crypto_ed25519_sign(g + 197, server_secret, message, strlen(domain) + 197);
	uint8_t digest[32], seed[32]; kr_hash(s->payload, s->payload_size, digest); kr_hex(digest, 32, s->hash);
	char transcript[768], signature[87]; int n = snprintf(transcript, sizeof(transcript),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\n%u\n",
		KR_SETTINGS_PROOF_VERSION_2, server_id, b->core.identity.serial, b->core.identity.key_id, s->device_id, s->owner_generation,
		s->sync_run, s->version, s->format, s->hash, s->nonce, s->nonce, epoch, generation); sign(transcript, (size_t)n, signature);
	struct kr_settings_auth auth = {.version2 = true, .ownership_epoch = generation, .protocol_version = KR_SETTINGS_PROOF_VERSION_2,
		.server_key_id = server_id, .signature = signature, .device_nonce = s->nonce, .server_time = "2026-11-02T00:05:00Z"};
	memset(seed, 0, 32); struct kr_settings_nonce nonce; int64_t verified;
	kr_issue_settings_nonce(&nonce, seed, 0); assert(!kr_verify_dev_settings(&b->core.identity, s, &auth, &nonce, 1, server_id, server, &verified));
	memset(seed, (int)(index + 40 + generation * 32), 32); assert(kr_peer_material_make(&material, &b->core.identity, s, &auth, seed));
	/* Model a completed, explicitly consented reset on this synthetic peer.
	 * The real reset signature/journal/power-cut path is exercised by test_reset. */
	memset(b->saved, 0, sizeof(b->saved)); memset(b->suppressed, 0, sizeof(b->suppressed)); memset(b->lengths, 0, sizeof(b->lengths));
	b->core.reset.ownership_epoch = generation;
	memset(&b->engine.config, 0, sizeof(b->engine.config)); b->engine.config.format = KR_VERSION; b->engine.config.device = material.device;
	restart_storage(b, &material); assert(!memcmp(&original, &b->core.identity, sizeof(original)));
}
static void test_owner_transition(FILE *scratch)
{
	boot(0, 1, scratch); boot(1, 2, scratch); exchange_pair(350);
	struct test_board *observer = &boards[0]; assert(kr_count(&observer->engine, KR_CONFIRMED) == 1);
	owner(observer, NULL); request(observer, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "CONFIRMED"); char old_hash[65]; field("envelopeHash", old_hash);
	uint8_t old_id[16]; memcpy(old_id, observer->engine.records[0].id, 16); struct kr_peer_material stale = boards[1].radio.own;
	rotate_owner(1, 3, 2, scratch); exchange_pair(450);
	assert(kr_count(&observer->engine, KR_CONFIRMED) == 2);
	request(observer, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", old_hash);
	assert(observer->app.scopes[0].epoch == 1 && observer->app.scopes[1].epoch == 2);
	assert(kr_ack(&observer->engine, old_id) == KR_OK && observer->engine.records[1].kind == KR_CONFIRMED);
	assert(observer->app.scopes[KR_RECORDS].epoch == 1);
	/* An old generation's release cannot delete/block another owner's original. */
	assert(kr_release(&observer->engine, old_id) == KR_OK && observer->engine.records[1].kind == KR_CONFIRMED);
	uint8_t second_id[16]; memcpy(second_id, observer->engine.records[1].id, 16);
	assert(kr_ack(&observer->engine, second_id) == KR_OK);
	rotate_owner(1, 4, 3, scratch); exchange_pair(450);
	assert(kr_count(&observer->engine, KR_CONFIRMED) == 1 && observer->app.scopes[KR_RECORDS].epoch == 2);
	/* Restore all raw/suppression scopes, retaining device keys and old proofs. */
	struct kr_peer_material own = observer->radio.own;
	memset(own.secret, 123, 32); crypto_x25519_public_key(own.public_key, own.secret);
	uint8_t binding[320]; const char *domain = "TINGLE-PEER-AUTH-V1\n";
	memcpy(binding, domain, strlen(domain)); memcpy(binding + strlen(domain), own.public_key, 32); memcpy(binding + strlen(domain) + 32, own.certificate, 261);
	crypto_ed25519_sign(own.certificate + 261, observer->core.identity.secret, binding, strlen(domain) + 32 + 261);
	restart_storage(observer, &own); exchange_pair(100);
	assert(kr_count(&observer->engine, KR_CONFIRMED) == 1 && observer->app.scopes[KR_RECORDS].epoch == 2);
	/* A holder of the old ephemeral key still cannot roll AUTH back after a
	 * newer ownership generation has been observed and restored. */
	static struct kr_engine replay_engine; static struct kr_peer_runtime replay_radio;
	struct kr_io io = {.ctx = &boards[3], .config = config, .record = raw_record, .suppression = suppression, .random = random_bytes};
	kr_init(&replay_engine, stale.device, io); assert(!kr_peer_start(&replay_radio, &stale, server, &replay_engine));
	uint8_t packet[512]; int sent;
	for (unsigned turn = 0; turn < 100; ++turn) {
		size_t n = kr_peer_advertisement(&observer->radio, &observer->engine, packet, &sent); assert(n);
		(void)kr_peer_receive(&replay_radio, &replay_engine, packet, n, (uint64_t)epoch);
		n = kr_peer_advertisement(&replay_radio, &replay_engine, packet, &sent); assert(n);
		(void)kr_peer_receive(&observer->radio, &observer->engine, packet, n, (uint64_t)epoch);
	}
	for (unsigned i = 0; i < KR_PEERS; ++i) {
		if (observer->radio.sessions[i].authenticated && observer->radio.sessions[i].remote.device == stale.device) { assert(observer->radio.sessions[i].remote.ownership_epoch == 3); }
	}
	assert(kr_count(&observer->engine, KR_CONFIRMED) == 1 && !observer->engine.storage_error);
	struct kr_peer_verified old_remote, new_remote;
	assert(kr_peer_certificate(stale.certificate, stale.public_key, server, (uint64_t)epoch, &old_remote));
	assert(kr_peer_certificate(boards[1].radio.own.certificate, boards[1].radio.own.public_key, server, (uint64_t)epoch, &new_remote));
	uint8_t bad_data[] = {1, 2, 3}; observer->radio.input_peer = &old_remote;
	uint64_t old_error = kr_product_error_key(&observer->app, stale.device, bad_data, sizeof(bad_data));
	observer->radio.input_peer = &new_remote;
	assert(old_error != kr_product_error_key(&observer->app, stale.device, bad_data, sizeof(bad_data))); observer->radio.input_peer = NULL;
	/* Reset must not silently discard unfinished or review-backpressured data. */
	struct kr_record pending = {.kind = KR_MATCH, .reason = KR_BAD_DATA}; observer->engine.records[10] = pending;
	assert(kr_pending(&observer->engine) == 2);
	observer->engine.records[10].kind = KR_EMPTY;
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) { observer->engine.suppressed[i].peer = 9 + i; }
	assert(!kr_can_insert(&observer->engine, KR_MATCH));
	puts("owner-flow: transfer with identical hardware/profile/revision, immutable old evidence, scoped ACK/release, reboot and old AUTH rejection passed");
}
static void test_candidate_and_completion(FILE *scratch)
{
	boot(0, 1, scratch); boot(1, 2, scratch); uint8_t packet[512]; int sent;
	/* Receive a complete authenticated profile, leaving its calculation queued. */
	for (unsigned turn = 0; turn < 120 && !kr_count(&boards[0].engine, KR_MATCH); ++turn) {
		for (unsigned i = 0; i < 2; ++i) {
			size_t n = kr_peer_advertisement(&boards[i].radio, &boards[i].engine, packet, &sent); assert(n);
			(void)kr_peer_receive(&boards[1 - i].radio, &boards[1 - i].engine, packet, n, (uint64_t)epoch);
		}
	}
	struct test_board *b = &boards[0]; assert(kr_count(&b->engine, KR_MATCH) == 1);
	b->ms = 6000; kr_drain(&b->engine); assert(b->engine.records[0].phase == KR_NEW);
	/* A departed first candidate cannot block another current peer. */
	boot(3, 4, scratch); boards[3].ms = 6000;
	for (unsigned turn = 0; turn < 350; ++turn) {
		for (unsigned step = 0; step < 2; ++step) {
			unsigned i = step ? 3 : 0, j = step ? 0 : 3;
			size_t n = kr_peer_advertisement(&boards[i].radio, &boards[i].engine, packet, &sent); assert(n);
			(void)kr_peer_receive(&boards[j].radio, &boards[j].engine, packet, n, (uint64_t)epoch + 6);
			kr_sent(&boards[i].engine, sent); kr_drain(&boards[i].engine);
		}
	}
	assert(b->engine.records[0].phase == KR_NEW && kr_count(&b->engine, KR_CONFIRMED) == 1);
	/* A validly signed certificate with a different owner at the same epoch
	 * is rejected; a newer settings revision in the SAME owner retires only
	 * the old unconfirmed input, retaining its frozen evidence. */
	uint8_t certificate[325], secret[32], public_key[32], signed_bytes[320];
	memcpy(certificate, boards[1].radio.own.certificate, sizeof(certificate)); memset(secret, 98, 32); crypto_x25519_public_key(public_key, secret);
	const char *grant_domain = "TINGLE-PEER-GRANT-V3\n", *auth_domain = "TINGLE-PEER-AUTH-V1\n";
	for (unsigned variant = 0; variant < 2; ++variant) {
		memcpy(certificate, boards[1].radio.own.certificate, sizeof(certificate));
		if (!variant) { certificate[32] ^= 7; } else { kr_put32(certificate + 17, 11); }
		memcpy(signed_bytes, grant_domain, strlen(grant_domain)); memcpy(signed_bytes + strlen(grant_domain), certificate, 197);
		crypto_ed25519_sign(certificate + 197, server_secret, signed_bytes, strlen(grant_domain) + 197);
		memcpy(signed_bytes, auth_domain, strlen(auth_domain)); memcpy(signed_bytes + strlen(auth_domain), public_key, 32); memcpy(signed_bytes + strlen(auth_domain) + 32, certificate, 261);
		crypto_ed25519_sign(certificate + 261, boards[1].core.identity.secret, signed_bytes, strlen(auth_domain) + 32 + 261);
		struct kr_peer_verified verified; assert(kr_peer_certificate(certificate, public_key, server, (uint64_t)epoch + 6, &verified));
		int result = b->radio.cache_certificate(b->radio.application, 15, certificate, public_key);
		if (!variant) { assert(result < 0 && b->engine.records[0].kind == KR_MATCH); }
		else { assert(!result && b->engine.records[0].kind == KR_REVIEW && b->engine.records[0].reason == KR_PEER_SETTINGS_CHANGED); }
	}
	/* Reserve/deletion races: an old completion cannot mark a reused slot done. */
	static struct kr_engine completion; struct kr_io io = {.ctx = &boards[2], .record = raw_record, .suppression = suppression};
	kr_init(&completion, 99, io); completion.records[0] = b->engine.records[1]; completion.records[0].phase = KR_FINAL;
	uint8_t id[16]; memcpy(id, completion.records[0].id, 16); uint32_t sequence = completion.records[0].sequence;
	completion.records[0].sequence++;
	assert(!kr_sent_guarded(&completion, 0, sequence, id) && completion.records[0].phase == KR_FINAL);
	assert(kr_sent_guarded(&completion, 0, sequence + 1, id) && completion.records[0].phase == KR_COMPLETE);
	completion.suppressed[0].peer = 3; completion.suppressed[0].phase = KR_FINAL; memcpy(completion.suppressed[0].id, id, 16); id[0] ^= 1;
	assert(!kr_sent_guarded(&completion, KR_RECORDS, 0, id) && completion.suppressed[0].phase == KR_FINAL);
	/* A peer cannot propose an arbitrarily future meeting timestamp. */
	kr_init(&completion, 2, io); completion.ready = true; completion.config.profile = boards[1].engine.config.profile;
	struct kr_record candidate = {.format = KR_VERSION, .kind = KR_MATCH, .phase = KR_WAIT, .peer = 1, .sequence = 1}; candidate.own = completion.config.profile;
	uint8_t profile_body[256]; kr_put64(profile_body, 1); size_t profile_len = kr_profile_encode(&b->engine.config.profile, profile_body + 8);
	candidate.peer_len = (uint16_t)kr_packet(1, profile_body, profile_len + 8, candidate.peer_wire); completion.records[0] = candidate;
	uint8_t control[65] = {0}; kr_put64(control, 1); kr_put64(control + 8, 2); control[16] = 7;
	kr_put64(control + 32, (uint64_t)epoch + 301); kr_put64(control + 40, (uint64_t)epoch + 301 + candidate.own.opportunity_sec);
	kr_put32(control + 56, b->engine.config.profile.revision); kr_put32(control + 60, candidate.own.revision); control[64] = KR_OFFER;
	size_t bytes = kr_packet(2, control, sizeof(control), packet); assert(kr_receive(&completion, packet, bytes, 1, (uint64_t)epoch) == KR_INVALID);
	puts("queue-flow: current presence recheck, other candidate progress and stale completion rejection passed");
}
static void store_settings(struct test_board *b, const struct kr_settings *settings)
{
	memset(b->sync_nonce, 87, 32); char device_nonce[44]; kr_base64_encode(b->sync_nonce, 32, device_nonce);
	char message[768], signature[87], stored[87]; int n = snprintf(message, sizeof(message),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\n1\n",
		KR_SETTINGS_PROOF_VERSION_2, server_id, b->core.identity.serial, b->core.identity.key_id, settings->device_id,
		settings->owner_generation, settings->sync_run, settings->version, settings->format, settings->hash, settings->nonce, device_nonce, epoch);
	sign(message, (size_t)n, signature);
	struct kr_settings_auth auth = {.version2 = true, .ownership_epoch = 1, .protocol_version = KR_SETTINGS_PROOF_VERSION_2,
		.server_key_id = server_id, .signature = signature, .device_nonce = device_nonce, .server_time = "2026-11-02T00:05:00Z"};
	assert(!kr_store(&b->core, settings, &auth, stored));
	assert(!memcmp(&b->core.current, settings, sizeof(*settings)) && !memcmp(&b->persisted, settings, sizeof(*settings)));
}
static void test_same_payload_resume(FILE *scratch)
{
	boot(0, 1, scratch); struct test_board *b = &boards[0]; struct kr_settings before = b->core.current;
	store_settings(b, &before);
	struct kr_peer_plan plan; assert(kr_peer_after_response(&b->core, true, &plan));
	assert(memcmp(plan.peer.public_key, b->radio.own.public_key, 32));
	puts("resume-flow: fresh nonce/outer signature can resume identical stored payload with a fresh ephemeral key");
}
static const char applied_request[] = "{\"type\":\"GET_SETTINGS_APPLIED_PROOF\",\"syncRunId\":\"22222222-2222-4222-8222-222222222222\"}";
static const char applied_off_request[] = "{\"type\":\"GET_SETTINGS_APPLIED_PROOF\",\"syncRunId\":\"33333333-3333-4333-8333-333333333333\"}";
static bool apply_reset_pending(void *ctx) { (void)ctx; return false; }
static int apply_settings(void *ctx, const struct kr_peer_plan *plan)
{
	struct test_board *b = ctx;
	assert(kr_settings_apply_current(&b->core, plan));
	assert(kr_product_apply(&b->app, &b->radio, plan->verified_time));
	if (b->fail_raw) { return -EIO; }
	kr_peer_stop(&b->radio); b->engine.ready = false;
	if (plan->peer.valid) {
		assert(!kr_peer_start(&b->radio, &plan->peer, server, &b->engine));
		kr_product_bind_peer(&b->app, &b->radio);
	}
	return 0;
}
static int apply_refresh(void *ctx) { return ((struct test_board *)ctx)->fail_counter ? -EIO : 0; }
static void apply_finished(void *ctx, const struct kr_peer_plan *plan, int error)
{ kr_settings_apply_finished(&((struct test_board *)ctx)->core, plan, !error); }

static void test_applied_proof(FILE *vectors, FILE *scratch)
{
	boot(0, 1, scratch);
	struct test_board *b = &boards[0];
	struct kr_settings settings = b->persisted;
	struct kr_peer_plan stale, plan;
	store_settings(b, &settings);
	assert(kr_peer_after_response(&b->core, true, &stale));
	store_settings(b, &settings);
	assert(kr_peer_after_response(&b->core, true, &plan));
	kr_settings_apply_finished(&b->core, &stale, true);
	request(b, applied_request, "\"code\":\"SETTINGS_APPLY_PENDING\"");
	request(b, applied_off_request, "\"code\":\"SETTINGS_RUN_MISMATCH\"");

	const struct kr_service_io io = {.ctx = b, .reset_pending = apply_reset_pending,
		.apply = apply_settings, .refresh_phone = apply_refresh, .apply_result = apply_finished};
	bool update = true;
	b->fail_raw = true;
	assert(kr_service_step(&plan, &update, &io).radio_error == -EIO && update);
	request(b, applied_request, "\"code\":\"SETTINGS_APPLY_FAILED\"");
	b->fail_raw = false;
	b->fail_counter = true;
	assert(kr_service_step(&plan, &update, &io).radio_error == -EIO && update);
	request(b, applied_request, "\"code\":\"SETTINGS_APPLY_FAILED\"");
	b->fail_counter = false;
	assert(kr_service_step(&plan, &update, &io).applied && !update);
	request(b, applied_request, "\"type\":\"SETTINGS_APPLIED_PROOF\"");
	vector(vectors, "appliedResponse", response, strlen(response));
	char first[KR_RESPONSE_MAX]; strcpy(first, response);
	++b->core.connection_epoch;
	request(b, applied_request, first);
	b->core.profile_transport_protected = false;
	request(b, applied_request, "\"code\":\"SECURE_TRANSPORT_REQUIRED\"");

	/* Boot restores durable settings and the device key, but no apply result. */
	b->core = (struct kr_core){.identity = b->core.identity, .current = b->persisted, .reset = b->core.reset,
		.platform = b->core.platform, .profile_transport_protected = true};
	kr_peer_stop(&b->radio); b->engine.ready = false;
	request(b, applied_request, "\"code\":\"SETTINGS_RUN_MISMATCH\"");
	store_settings(b, &settings);
	assert(!kr_peer_after_response(&b->core, false, &plan));
	request(b, applied_request, "\"code\":\"SETTINGS_APPLY_FAILED\"");
	store_settings(b, &settings);
	assert(kr_peer_after_response(&b->core, true, &plan));
	update = true;
	assert(kr_service_step(&plan, &update, &io).applied);
	request(b, applied_request, first);

	/* A newer signed TSV3 run applies the OFF policy and stops radio work. */
	++settings.version;
	strcpy(settings.sync_run, "33333333-3333-4333-8333-333333333333");
	settings.payload[199] &= ~1u;
	uint8_t *grant = settings.payload + 200;
	kr_put32(grant + 17, (uint32_t)settings.version);
	memset(grant + 45, 0x33, 16); grant[51] = 0x43; grant[53] = 0x83;
	kr_hash(settings.payload + 4, 196, grant + 133);
	uint8_t message[sizeof("TINGLE-PEER-GRANT-V3\n") - 1 + 197] = "TINGLE-PEER-GRANT-V3\n";
	memcpy(message + sizeof(message) - 197, grant, 197);
	crypto_ed25519_sign(grant + 197, server_secret, message, sizeof(message));
	uint8_t digest[32]; kr_hash(settings.payload, settings.payload_size, digest); kr_hex(digest, 32, settings.hash);
	store_settings(b, &settings);
	assert(kr_peer_after_response(&b->core, true, &plan) && !plan.length && !plan.peer.valid);
	update = true;
	assert(kr_service_step(&plan, &update, &io).applied && !kr_product_ready(&b->app));
	request(b, applied_off_request, "\"type\":\"SETTINGS_APPLIED_PROOF\"");
	char expected[64]; snprintf(expected, sizeof(expected), "\"settingsVersion\":%" PRId64 ",", settings.version);
	assert(strstr(response, expected) && strstr(response, settings.hash));
	request(b, applied_request, "\"code\":\"SETTINGS_RUN_MISMATCH\"");
	++b->core.reset.ownership_epoch;
	request(b, applied_off_request, "\"code\":\"SETTINGS_RUN_MISMATCH\"");
	puts("applied-proof: pending, failures, retry/reconnect, reboot, OFF and stale run/owner checks passed");
}
static enum kr_decision fail_calculation(void *ctx, const struct kr_profile *a, const struct kr_profile *b)
{ (void)ctx; (void)a; (void)b; return KR_CALC_ERROR; }
static void update_revision(unsigned index, uint32_t revision)
{
	struct test_board *b = &boards[index]; struct kr_settings *s = &b->core.current;
	s->version = revision; uint8_t *g = s->payload + 200; kr_put32(g + 17, revision);
	uint8_t signed_bytes[224]; const char *domain = "TINGLE-PEER-GRANT-V3\n";
	memcpy(signed_bytes, domain, strlen(domain)); memcpy(signed_bytes + strlen(domain), g, 197);
	crypto_ed25519_sign(g + 197, server_secret, signed_bytes, strlen(domain) + 197);
	uint8_t digest[32], seed[32]; kr_hash(s->payload, s->payload_size, digest); kr_hex(digest, 32, s->hash);
	char message[768], signature[87]; int n = snprintf(message, sizeof(message),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\n1\n",
		KR_SETTINGS_PROOF_VERSION_2, server_id, b->core.identity.serial, b->core.identity.key_id, s->device_id,
		s->owner_generation, s->sync_run, s->version, s->format, s->hash, s->nonce, s->nonce, epoch);
	sign(message, (size_t)n, signature);
	struct kr_settings_auth auth = {.version2 = true, .ownership_epoch = 1, .protocol_version = KR_SETTINGS_PROOF_VERSION_2,
		.server_key_id = server_id, .signature = signature, .device_nonce = s->nonce, .server_time = "2026-11-02T00:05:00Z"};
	memset(seed, 0, 32); struct kr_settings_nonce nonce; int64_t verified;
	kr_issue_settings_nonce(&nonce, seed, 0); assert(!kr_verify_dev_settings(&b->core.identity, s, &auth, &nonce, 1, server_id, server, &verified));
	struct kr_peer_material material; memset(seed, (int)(150 + index + revision), 32);
	assert(kr_peer_material_make(&material, &b->core.identity, s, &auth, seed)); b->persisted = *s; restart_storage(b, &material);
}
static void test_failed_input_change(FILE *scratch)
{
	boot(0, 1, scratch); boot(1, 2, scratch);
	for (unsigned i = 0; i < 2; ++i) { boards[i].engine.io.calculate = fail_calculation; }
	exchange_pair(350); struct test_board *a = &boards[0], *b = &boards[1];
	assert(kr_count(&a->engine, KR_REVIEW) == 1 && kr_count(&b->engine, KR_REVIEW) == 1);
	owner(b, NULL); request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "REVIEW"); char old_hash[65]; field("envelopeHash", old_hash);
	uint8_t id[16]; memcpy(id, a->engine.records[0].id, 16); assert(kr_ack(&a->engine, id) == KR_OK);
	assert(a->app.peer_revisions[KR_RECORDS] == 10 && a->lengths[900] == 88);
	exchange_pair(100); assert(!kr_count(&a->engine, KR_MATCH) && !kr_count(&a->engine, KR_REVIEW));
	/* Same owner/profile, but a new signed remote input version permits a
	 * fresh calculation without mutating the already exported failure. */
	struct kr_peer_material own = a->radio.own; restart_storage(a, &own); update_revision(1, 11);
	assert(a->app.peer_revisions[KR_RECORDS] == 10); exchange_pair(450);
	assert(kr_count(&a->engine, KR_CONFIRMED) == 1 && kr_count(&b->engine, KR_CONFIRMED) == 1);
	owner(b, NULL); request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", old_hash);
	assert(kr_count(&b->engine, KR_REVIEW) == 1);
	own = a->radio.own; restart_storage(a, &own); exchange_pair(100);
	assert(kr_count(&a->engine, KR_CONFIRMED) == 1 && !a->engine.storage_error);
	puts("retry-flow: identical failed input stays suppressed; changed signed input retries after ACK/reboot with immutable old proof");
}
void test_power_monitor(void);
static void test_carrier_feedback(void)
{
	const uint8_t event[16] = {23};
	struct kr_profile own = {.data_len = sizeof(profile)}, peer = {.data_len = sizeof(profile)};
	memcpy(own.data, profile, sizeof(profile));
	memcpy(peer.data, profile, sizeof(profile));
	for (uint8_t a = 0; a < 8; ++a) {
		for (uint8_t b = 0; b < 8; ++b) {
			own.data[195] = a;
			peer.data[195] = b;
			const bool matching = (a & b & 1) != 0;
			struct kr_feedback_options left = kr_feedback_options(event, &own, &peer);
			struct kr_feedback_options right = kr_feedback_options(event, &peer, &own);
			assert((left.rgb != 0) == (matching && (a & 4)));
			assert((right.rgb != 0) == (matching && (b & 4)));
			if (left.rgb && right.rgb) { assert(left.rgb == right.rgb); }
			assert(left.vibrate == (matching && (a & b & 2)) && left.vibrate == right.vibrate);
		}
	}
	peer.data[0] ^= 1;
	struct kr_feedback_options invalid = kr_feedback_options(event, &own, &peer);
	assert(!invalid.rgb && !invalid.vibrate);

	struct kr_status power = {.usb_valid = true, .battery_mv = 3850, .charging = 0};
	assert(kr_feedback_motor_can_start(&power, false));
	assert(kr_feedback_motor_can_start(&power, true));
	power.usb_present = true;
	power.charging = 1;
	assert(!kr_feedback_motor_can_start(&power, false));
	assert(kr_feedback_motor_can_start(&power, true));
	power.usb_valid = false;
	assert(!kr_feedback_motor_can_start(&power, true));
	power.usb_valid = true;
	power.usb_present = false;
	power.charging = -1;
	assert(!kr_feedback_motor_can_start(&power, true));
	power.charging = 0;
	power.battery_mv = 3299;
	assert(!kr_feedback_motor_can_start(&power, true));
	power.battery_mv = 3850;
	power.presence_valid = true;
	assert(!kr_feedback_motor_can_start(&power, true));

	struct kr_feedback alert = {0};
	const struct kr_feedback_options both = {.rgb = 3, .vibrate = true};
	const int64_t start = 5000;
	assert(kr_feedback_start(&alert, start, both));
	assert(kr_feedback_step(&alert, start, true).motor);
	assert(!kr_feedback_start(&alert, start + 50, both));
	assert(kr_feedback_step(&alert, start + 119, true).motor);
	assert(!kr_feedback_step(&alert, start + 120, true).motor);
	assert(kr_feedback_step(&alert, start + 270, true).motor);
	assert(kr_feedback_step(&alert, start + 389, true).motor);
	assert(!kr_feedback_step(&alert, start + 390, true).motor);
	assert(kr_feedback_step(&alert, start + 29999, true).rgb == 3);
	assert(!kr_feedback_step(&alert, start + 30000, true).wake_ms);

	assert(kr_feedback_start(&alert, start, both));
	assert(kr_feedback_step(&alert, start, true).motor);
	assert(!kr_feedback_step(&alert, start + 10, false).motor);
	struct kr_feedback_output out = kr_feedback_step(&alert, start + 270, true);
	assert(!out.motor && out.rgb == 3);
	assert(kr_feedback_start(&alert, start, both));
	assert(!kr_feedback_step(&alert, start + 120, true).motor);
	kr_feedback_stop(&alert);
	out = kr_feedback_step(&alert, start + 270, true);
	assert(!out.motor && !out.rgb && !out.wake_ms);
	puts("carrier feedback: Rev G/D3.1 power policy, bilateral switches, pulse/LED timing and power-change cancellation passed");
}

static void test_charge_indicator(void)
{
	struct kr_charge_indicator indicator = {0};
	struct kr_status status = {.usb_valid = true, .usb_present = true, .charging = 1, .battery_mv = 3569};
	for (unsigned i = 0; i < 4; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_LOW);
	status.battery_mv = 3570; /* 30% in the existing voltage estimate. */
	for (unsigned i = 0; i < 4; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_LOW); }
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_MID);
	status.battery_mv = 3930; /* 70% */
	for (unsigned i = 0; i < 4; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_MID); }
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_HIGH);
	status.charging = 0;
	status.battery_mv = 4200;
	for (unsigned i = 0; i < 5; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	status.charging = 1;
	status.presence_valid = true;
	for (unsigned i = 0; i < 5; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	status.battery_present = true;
	status.battery_mv = -1;
	for (unsigned i = 0; i < 5; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	status.gauge_ready = status.gauge_percent_valid = true;
	status.gauge_percent = 70;
	for (unsigned i = 0; i < 4; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_HIGH);
	status.usb_present = false;
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF);
	/* Validate both sides of the 30/70 boundaries using true SOC when present.
	 * A broken gauge must never silently fall back to a plausible ADC value. */
	status.usb_present = true;
	status.battery_mv = 4200;
	const int percentages[] = {-1, 0, 29, 30, 69, 70, 100, 101};
	const enum kr_charge_level levels[] = {KR_CHARGE_OFF, KR_CHARGE_LOW, KR_CHARGE_LOW,
		KR_CHARGE_MID, KR_CHARGE_MID, KR_CHARGE_HIGH, KR_CHARGE_HIGH, KR_CHARGE_OFF};
	const uint8_t colors[] = {0, 1, 1, 4, 4, 2, 2, 0};
	for (unsigned n = 0; n < sizeof(percentages) / sizeof(percentages[0]); ++n) {
		status.gauge_percent = percentages[n];
		for (unsigned i = 0; i < 5; ++i) { (void)kr_charge_indicator_update(&indicator, status); }
		assert(indicator.level == levels[n]);
		assert(kr_charge_indicator_rgb(indicator.level) == colors[n]);
	}
	status.gauge_percent = 80;
	status.gauge_percent_valid = false;
	for (unsigned i = 0; i < 5; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	status.gauge_ready = false;
	status.charging = 1;
	status.battery_mv = 3929;
	for (unsigned i = 0; i < 5; ++i) { (void)kr_charge_indicator_update(&indicator, status); }
	assert(indicator.level == KR_CHARGE_MID);
	/* Alternating samples do not cause a threshold flicker. */
	for (unsigned i = 0; i < 10; ++i) {
		status.battery_mv = i % 2 ? 3929 : 3930;
		assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_MID);
	}
	assert(kr_battery_reading(status).percent == -1); /* LED estimate does not change BLE SOC. */
	status.battery_mv = 4301;
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF);
	status.battery_mv = 2999;
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF);
	status.battery_mv = 3930;
	status.charging = -1;
	for (unsigned i = 0; i < 5; ++i) { assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF); }
	status.charging = 1;
	status.usb_valid = false;
	assert(kr_charge_indicator_update(&indicator, status) == KR_CHARGE_OFF);
	/* No mixed boot-green/red or stale blue after a diagnostic ends. */
	assert(kr_status_led_rgb(KR_CHARGE_LOW, true, false, false) == 1);
	assert(kr_status_led_rgb(KR_CHARGE_MID, true, false, false) == 4);
	assert(kr_status_led_rgb(KR_CHARGE_HIGH, true, false, false) == 2);
	assert(kr_status_led_rgb(KR_CHARGE_OFF, true, false, false) == 0);
	assert(kr_status_led_rgb(KR_CHARGE_HIGH, true, true, false) == 1);
	assert(kr_status_led_rgb(KR_CHARGE_LOW, true, false, true) == 4);
	assert(kr_status_led_rgb(KR_CHARGE_OFF, false, false, false) == 2);
	puts("charge LED: 30/70% RGB, stability, unknown/absent/unplugged, gauge failures and onboard priority passed");
}

static int damaged_settings_read(void *ctx, uint16_t key, unsigned history, struct kr_settings *out)
{
	if (key != KR_SETTINGS_CURRENT_KEY || history) { return -ENOENT; }
	memcpy(out, ctx, sizeof(*out)); return -EIO;
}
static bool recovery_owner(void *ctx, const struct kr_core *core, const struct kr_settings *s)
{ (void)ctx; return kr_settings_recovery_owner(core, s, server); }

static void test_signed_owner_recovery(struct test_board *b)
{
	struct kr_core core = {0}; core.identity = b->core.identity;
	core.reset.magic = KR_RESET_MAGIC;
	struct kr_settings damaged = b->core.current;
	uint8_t *g = damaged.payload + 200;
	/* A correctly signed epoch zero models the first owner, with no reset. */
	kr_put32(g + 21, 0);
	const char domain[] = "TINGLE-PEER-GRANT-V3\n";
	uint8_t message[224], digest[32];
	memcpy(message, domain, sizeof(domain) - 1); memcpy(message + sizeof(domain) - 1, g, 197);
	crypto_ed25519_sign(g + 197, server_secret, message, sizeof(domain) - 1 + 197);
	kr_hash(damaged.payload, damaged.payload_size, digest); kr_hex(digest, 32, damaged.hash);
	/* Corrupt unused bytes: payload hash still passes, NVS CRC does not. */
	damaged.payload[1500] = 0xff;
	struct kr_settings original = damaged;
	struct kr_identity original_key = core.identity;
	const struct kr_settings_storage io = {.ctx = &damaged, .read = damaged_settings_read, .recover_owner = recovery_owner};
	assert(kr_settings_storage_load(&core, &io) == 2);
	assert(core.settings_owner_only && core.settings_recovery_required && !core.storage_error);
	assert(!memcmp(&damaged, &original, sizeof(damaged)) && !memcmp(&core.identity, &original_key, sizeof(original_key)));
	char signature[87]; core.profile_transport_protected = true;
	struct kr_settings_auth auth = {.version2 = true};
	assert(!strcmp(kr_store(&core, &original, &auth, signature), "POLICY_UNRESOLVED"));
	assert(kr_settings_applied_proof(&core, original.sync_run, signature));
	/* Same run/version can be repaired only with a NEW nonce-bound server proof. */
	struct kr_settings clean = original, saved = b->persisted;
	memset(clean.payload + clean.payload_size, 0, KR_PAYLOAD_MAX - clean.payload_size);
	memset(b->sync_nonce, 7, sizeof(b->sync_nonce)); char device_nonce[44], text[768];
	kr_base64_encode(b->sync_nonce, 32, device_nonce);
	int n = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\n0\n",
		KR_SETTINGS_PROOF_VERSION_2, server_id, core.identity.serial, core.identity.key_id, clean.device_id,
		clean.owner_generation, clean.sync_run, clean.version, clean.format, clean.hash, clean.nonce, device_nonce, epoch);
	char server_proof[87]; sign(text, (size_t)n, server_proof);
	auth = (struct kr_settings_auth){.version2 = true, .ownership_epoch = 0, .protocol_version = KR_SETTINGS_PROOF_VERSION_2,
		.server_key_id = server_id, .device_nonce = device_nonce, .server_time = "2026-11-02T00:05:00Z", .signature = server_proof};
	core.platform = b->core.platform; core.platform.application = NULL;
	server_proof[0] = server_proof[0] == 'A' ? 'B' : 'A';
	assert(!strcmp(kr_store(&core, &clean, &auth, signature), "INVALID_SERVER_PROOF"));
	sign(text, (size_t)n, server_proof);
	assert(!kr_store(&core, &clean, &auth, signature));
	assert(!core.settings_recovery_required && !core.settings_owner_only && !memcmp(&clean, &b->persisted, sizeof(clean)));
	assert(!memcmp(&core.identity, &original_key, sizeof(original_key)));
	b->persisted = saved; memset(b->sync_nonce, 0, sizeof(b->sync_nonce));
	++damaged.version; assert(kr_settings_storage_load(&core, &io) == -EILSEQ); damaged = original;
	damaged.owner_generation[0] = 'f'; assert(kr_settings_storage_load(&core, &io) == -EILSEQ); damaged = original;
	damaged.sync_run[0] = 'f'; assert(kr_settings_storage_load(&core, &io) == -EILSEQ); damaged = original;
	damaged.nonce[0] = 'B'; assert(kr_settings_storage_load(&core, &io) == -EILSEQ); damaged = original;
	damaged.payload[200 + 197] ^= 1;
	kr_hash(damaged.payload, damaged.payload_size, digest); kr_hex(digest, 32, damaged.hash);
	assert(kr_settings_storage_load(&core, &io) == -EILSEQ); damaged = original;
	core.identity.public_key[0] ^= 1; assert(kr_settings_storage_load(&core, &io) == -EILSEQ); core.identity = original_key;
	core.reset.ownership_epoch = 1; assert(kr_settings_storage_load(&core, &io) == -EILSEQ); core.reset.ownership_epoch = 0;
	core.reset.phase = KR_RESETTING; assert(kr_settings_storage_load(&core, &io) == -EILSEQ);
	core.reset.phase = KR_RESET_DONE; strcpy(core.reset.owner_generation, original.owner_generation);
	assert(kr_settings_storage_load(&core, &io) == -EILSEQ);
	puts("signed owner recovery: CRC failure quarantined, key/data retained, no APPLIED/replay/owner/epoch substitution");
}

int main(int argc, char **argv)
{
	assert(argc == 3); FILE *f = fopen(argv[1], "rb"); assert(f && fread(profile, 1, 196, f) == 196 && !fclose(f));
	test_power_monitor(); test_carrier_feedback();
	test_charge_indicator();
	f = fopen(argv[2], "wb"); assert(f); uint8_t seed[32]; for (unsigned i = 0; i < 32; ++i) { seed[i] = (uint8_t)i; }
	crypto_ed25519_key_pair(server_secret, server, seed); kr_hex(server, 8, server_id + 12); vector(f, "serverPublic", server, 32);
	boot(0, 1, f); boot(1, 2, f); boot(2, 1, f);
	test_signed_owner_recovery(&boards[0]);
	test_public_name(&boards[2]);
	struct test_board *b = &boards[0];
	request(b, "{\"type\":\"GET_DEVICE_INFO\"}", "tingle-keyring-settings-v3");
	assert(strstr(response, "\"GET_SETTINGS_APPLIED_PROOF\"") && strlen(response) < KR_RESPONSE_MAX - 1);
	assert(strstr(response, "\"maxPublicNameBytes\":29") && strstr(response, "\"SET_PUBLIC_NAME\""));
	request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "OWNER_SESSION_REQUIRED");
	request(b, "{\"type\":\"GET_STATUS\"}", "\"storageTotalBytes\":null");
	assert(strstr(response, "\"batteryProtectionState\":\"MEASUREMENT_UNKNOWN\"") &&
		strstr(response, "\"configuredChargeCurrentMa\":null") && strlen(response) < 2304);
	owner(b, f); request(b, "{\"type\":\"GET_STATUS\"}", "\"storageTotalBytes\":262144"); assert(strstr(response, "VOLTAGE_ESTIMATE"));
	vector(f, "statusResponse", response, strlen(response));
	bool applied_before_recovery = kr_product_applied(&b->app);
	b->core.settings_recovery_required = true;
	assert(!kr_product_applied(&b->app) && !kr_product_ready(&b->app));
	request(b, "{\"type\":\"GET_DEVICE_INFO\"}", "\"settingsRecoveryRequired\":true");
	assert(strstr(response, "\"storageHealthy\":false"));
	request(b, "{\"type\":\"GET_STATUS\"}", "\"settingsRecoveryRequired\":true");
	assert(strstr(response, "\"applied\":false") && strstr(response, "\"matchingReady\":false"));
	assert(strstr(response, "\"storageState\":\"AVAILABLE\"")); /* Record storage remains usable. */
	b->core.settings_recovery_required = false;
	assert(kr_product_applied(&b->app) == applied_before_recovery);
	++b->core.connection_epoch; request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "OWNER_SESSION_REQUIRED"); owner(b, NULL);
	++b->core.reset.ownership_epoch; request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "OWNER_SESSION_REQUIRED"); --b->core.reset.ownership_epoch;
	b->core.profile_transport_protected = false; request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "OWNER_SESSION_REQUIRED"); b->core.profile_transport_protected = true;
	uint8_t packet[512]; int sent;
	for (unsigned turn = 0; turn < 400; ++turn) {
		for (unsigned a = 0; a < 3; ++a) {
			size_t n = kr_peer_advertisement(&boards[a].radio, &boards[a].engine, packet, &sent); assert(n);
			for (unsigned j = 0; j < 3; ++j) { if (j != a) { (void)kr_peer_receive(&boards[j].radio, &boards[j].engine, packet, n, (uint64_t)epoch); } }
			kr_sent(&boards[a].engine, sent); kr_drain(&boards[a].engine);
		}
	}
	assert(kr_count(&b->engine, KR_CONFIRMED) == 1);
	for (unsigned i = 0; i < KR_RECORDS; ++i) { assert(!b->engine.records[i].kind || b->engine.records[i].peer != boards[2].engine.config.device); }
	request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "\"kind\":\"CONFIRMED\"");
	char hash[65], event[37], submission[37]; field("envelopeHash", hash); field("eventId", event); field("submissionId", submission);
	int64_t sequence = number("sequence"), size = number("totalBytes"); uint8_t envelope[2048]; size_t offset = 0;
	while (offset < (size_t)size) {
		char json[400], encoded[684]; snprintf(json, sizeof(json), "{\"type\":\"READ_RECORD_CHUNK\",\"sequence\":%" PRId64 ",\"offset\":%u,\"length\":512,\"envelopeHash\":\"%s\"}", sequence, (unsigned)offset, hash);
		request(b, json, "RECORD_CHUNK"); field("data", encoded); int n = kr_base64_decode(encoded, envelope + offset, sizeof(envelope) - offset); assert(n > 0); offset += (size_t)n;
	}
	assert(offset == (size_t)size && kr_count(&b->engine, KR_CONFIRMED) == 1); vector(f, "encounter", envelope, offset);
	uint8_t digest[32]; char actual[65]; kr_hash(envelope, offset, digest); kr_hex(digest, 32, actual); assert(!strcmp(actual, hash));
	char json[1800], transcript[1200], signature[87]; int n = snprintf(transcript, sizeof(transcript),
		"TINGLE-RECORD-RECEIPT\n%s\n%s\n%s\n%s\n100\n%s\n1\n%s\n%s\n%s\n44444444-4444-4444-8444-444444444444\n%" PRId64 "\nSTORED\nDELETE_ORIGINAL\n",
		KR_RECEIPT_PROOF, server_id, b->core.identity.serial, b->core.identity.key_id, b->core.current.owner_generation, event, submission, hash, epoch);
	sign(transcript, (size_t)n, signature); vector(f, "receiptTranscript", transcript, (size_t)n);
	uint8_t sig[64]; assert(kr_base64_decode(signature, sig, 64) == 64); vector(f, "receiptSignature", sig, 64);
	snprintf(json, sizeof(json), "{\"type\":\"ACK_RECORD\",\"proofProtocolVersion\":\"%s\",\"serverKeyId\":\"%s\",\"deviceId\":100,\"ownerGeneration\":\"%s\",\"ownershipEpoch\":1,"
		"\"eventId\":\"%s\",\"submissionId\":\"%s\",\"envelopeHash\":\"%s\",\"receiptId\":\"44444444-4444-4444-8444-444444444444\","
		"\"receivedAtEpochSeconds\":%" PRId64 ",\"receiptStatus\":\"STORED\",\"deletePermission\":\"DELETE_ORIGINAL\",\"serverSignature\":\"%s\"}",
		KR_RECEIPT_PROOF, server_id, b->core.current.owner_generation, event, submission, hash, epoch, signature);
	char bad[1800]; strcpy(bad, json); char *at = strstr(bad, "\"serverSignature\":\"") + strlen("\"serverSignature\":\""); *at = *at == 'A' ? 'B' : 'A';
	request(b, bad, "INVALID_SERVER_PROOF"); assert(kr_count(&b->engine, KR_CONFIRMED) == 1);
	/* Evidence/hash remain frozen after a settings change and process restore. */
	++b->core.current.version; request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", hash);
	assert(kr_restored_valid(&b->engine)); assert(!b->engine.ready); request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", hash);
	/* A full OFF plan still advances policy/time after STORED, with no radio. */
	b->core.peer_after_stored.verified_time = epoch; b->core.peer_after_stored.length = 0;
	struct kr_peer_plan off; assert(kr_peer_after_response(&b->core, true, &off) && !off.length);
	b->app.permissions = KR_OWNER_READ; request(b, json, "OWNER_SESSION_REQUIRED"); b->app.permissions = 15;
	/* Crash/failure between suppression commit and original deletion: retain
	 * the original and recover idempotently without creating another event. */
	b->fail_raw = true; request(b, json, "STORAGE_ERROR"); assert(kr_count(&b->engine, KR_CONFIRMED) == 1);
	b->fail_raw = false; b->engine.storage_error = false;
	memcpy(b->engine.records, b->saved, sizeof(b->saved)); memcpy(b->engine.suppressed, b->suppressed, sizeof(b->suppressed));
	assert(kr_restored_valid(&b->engine)); request(b, json, "REMOVED_OR_ALREADY_REMOVED");
	assert(!kr_count(&b->engine, KR_CONFIRMED)); request(b, json, "REMOVED_OR_ALREADY_REMOVED");
	unsigned suppression_count = 0; for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) { suppression_count += b->engine.suppressed[i].peer != 0; } assert(suppression_count);
	uint64_t peer = 0; for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) { if (b->engine.suppressed[i].peer) { peer = b->engine.suppressed[i].peer; break; } }
	n = snprintf(transcript, sizeof(transcript),
		"TINGLE-RECORD-RELEASE\ntingle-record-release-v2\n%s\n%s\n%s\n100\n%s\n1\n%s\n%016" PRIx64 "\n44444444-4444-4444-8444-444444444444\n%" PRId64 "\nRELEASE_AFTER_DEPARTURE\n",
		server_id, b->core.identity.serial, b->core.identity.key_id, b->core.current.owner_generation, event, peer, epoch);
	sign(transcript, (size_t)n, signature); vector(f, "releaseTranscript", transcript, (size_t)n);
	assert(kr_base64_decode(signature, sig, 64) == 64); vector(f, "releaseSignature", sig, 64);
	snprintf(json, sizeof(json), "{\"type\":\"RELEASE_RECORD\",\"proofProtocolVersion\":\"tingle-record-release-v2\",\"serverKeyId\":\"%s\",\"deviceId\":100,"
		"\"ownerGeneration\":\"%s\",\"ownershipEpoch\":1,\"eventId\":\"%s\",\"peerDeviceRefHex\":\"%016" PRIx64 "\","
		"\"receiptId\":\"44444444-4444-4444-8444-444444444444\",\"receivedAtEpochSeconds\":%" PRId64 ",\"result\":\"RELEASE_AFTER_DEPARTURE\",\"serverSignature\":\"%s\"}",
		server_id, b->core.current.owner_generation, event, peer, epoch, signature);
	request(b, json, "DEPARTURE_NOT_CONFIRMED"); b->ms = 6000; kr_product_tick(&b->app);
	/* Restore the active matcher after the earlier simulated storage reboot. */
	b->engine.ready = true; b->app.seen[0] = (uint64_t)epoch; kr_product_tick(&b->app);
	request(b, json, "RELEASED"); request(b, json, "RELEASED");
	request(b, "{\"type\":\"STOP_FEEDBACK\"}", "FEEDBACK_RESULT"); assert(b->stops == 1);
	/* Trusted original deadline expires a record; boot without time cannot. */
	struct test_board *other = &boards[1]; assert(kr_count(&other->engine, KR_CONFIRMED) == 1);
	other->app.clock_ready = false; other->ms = 4LL * 86400000; kr_product_tick(&other->app); assert(kr_count(&other->engine, KR_CONFIRMED) == 1);
	other->app.clock_ready = true; kr_product_tick(&other->app); assert(!kr_count(&other->engine, KR_CONFIRMED));
	/* Account suppression survives raw removal and application reconstruction. */
	struct kr_product recovered; struct kr_product_io recovered_io = other->app.io;
	other->engine.io.record = raw_record; other->engine.io.suppression = suppression;
	kr_product_init(&recovered, &other->core, &other->engine, recovered_io, server);
	unsigned refs = 0; for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		if (other->engine.suppressed[i].peer && !(other->engine.suppressed[i].peer >> 63)) {
			assert(!memcmp(recovered.accounts[KR_RECORDS + i], b->radio.own.owner_ref, 16)); ++refs;
		}
	} assert(refs == 1);
	/* Pre-v0.6 suppression without account metadata is held, then restored
	 * only by a server signature bound to this exact device/event/peer. */
	other->lengths[900] = 0;
	kr_product_init(&other->app, &other->core, &other->engine, recovered_io, server);
	other->engine.io.record = record; other->engine.io.suppression = wrapped_suppression;
	assert(!kr_product_ready(&other->app));
	assert(kr_product_apply(&other->app, &other->radio, epoch));
	struct kr_peer_material restored_material = other->radio.own;
	assert(!kr_peer_start(&other->radio, &restored_material, server, &other->engine));
	kr_product_bind_peer(&other->app, &other->radio);
	kr_product_tick(&other->app);
	assert(other->engine.ready && !kr_product_ready(&other->app));
	assert(!other->app.seen[0]); owner(other, NULL);
	request(other, "{\"type\":\"GET_STATUS\"}", "\"unresolvedAccountLockCount\":1");
	char owner_ref[33]; kr_hex(b->radio.own.owner_ref, 16, owner_ref);
	uint8_t peer_credential[357]; char peer_encoded[480], peer_hash[65];
	memcpy(peer_credential, b->radio.own.certificate, 325); memcpy(peer_credential + 325, b->radio.own.public_key, 32);
	struct kr_peer_verified restored_peer;
	assert(kr_peer_certificate(peer_credential, peer_credential + 325, server, (uint64_t)epoch, &restored_peer));
	assert(!other->radio.allowed(other->radio.application, &restored_peer, (uint64_t)epoch));
	kr_base64_encode(peer_credential, sizeof(peer_credential), peer_encoded); kr_hash(peer_credential, sizeof(peer_credential), digest); kr_hex(digest, 32, peer_hash);
	vector(f, "peerCredential", peer_credential, sizeof(peer_credential));
	n = snprintf(transcript, sizeof(transcript),
		"TINGLE-ACCOUNT-LOCK\ntingle-account-lock-v2\n%s\n%s\n%s\n101\n%s\n1\n%s\n%016" PRIx64 "\n%s\n44444444-4444-4444-8444-444444444444\n%" PRId64 "\n%s\n",
		server_id, other->core.identity.serial, other->core.identity.key_id, other->core.current.owner_generation, event, b->engine.config.device, owner_ref, epoch, peer_hash);
	sign(transcript, (size_t)n, signature); vector(f, "accountLockTranscript", transcript, (size_t)n);
	assert(kr_base64_decode(signature, sig, 64) == 64); vector(f, "accountLockSignature", sig, 64);
	snprintf(json, sizeof(json), "{\"type\":\"RESTORE_ACCOUNT_LOCK\",\"proofProtocolVersion\":\"tingle-account-lock-v2\",\"serverKeyId\":\"%s\",\"deviceId\":101,"
		"\"ownerGeneration\":\"%s\",\"ownershipEpoch\":1,\"eventId\":\"%s\",\"ownerRefHex\":\"%s\",\"grantId\":\"44444444-4444-4444-8444-444444444444\","
		"\"issuedAtEpochSeconds\":%" PRId64 ",\"serverSignature\":\"%s\",\"peerCredential\":\"%s\"}", server_id, other->core.current.owner_generation, event, owner_ref, epoch, signature, peer_encoded);
	strcpy(bad, json); at = strstr(bad, "\"serverSignature\":\"") + strlen("\"serverSignature\":\""); *at = *at == 'A' ? 'B' : 'A';
	request(other, bad, "INVALID_SERVER_PROOF");
	strcpy(bad, json); at = strstr(bad, "\"peerCredential\":\"") + strlen("\"peerCredential\":\""); *at = *at == 'A' ? 'B' : 'A';
	request(other, bad, "INVALID_PEER_CREDENTIAL");
	strcpy(bad, json); at = strstr(bad, "tingle-account-lock-v2"); at[strlen("tingle-account-lock-v2") - 1] = '1';
	request(other, bad, "PEER_SCOPE_REQUIRED"); request(other, json, "ACCOUNT_LOCK_RESULT"); request(other, json, "ACCOUNT_LOCK_RESULT");
	request(other, "{\"type\":\"GET_STATUS\"}", "\"unresolvedAccountLockCount\":0");
	assert(kr_product_ready(&other->app)); kr_product_tick(&other->app);
	assert(kr_product_ready(&other->app));
	assert(other->radio.allowed(other->radio.application, &restored_peer, (uint64_t)epoch));
	other->app.policy.enabled = false; assert(!kr_product_ready(&other->app)); other->app.policy.enabled = true;
	other->engine.storage_error = true; assert(!kr_product_ready(&other->app));
	other->engine.storage_error = false;
	other->ms += (int64_t)(other->app.policy.valid_until - kr_product_now(&other->app) + 1) * 1000;
	kr_product_tick(&other->app); assert(!other->engine.ready && !kr_product_ready(&other->app));
	/* An old Lab record has no invented proof and remains queued. */
	b->engine.records[0] = other->saved[0]; b->engine.records[0].kind = KR_REVIEW; b->engine.records[0].sequence = 999;
	request(b, "{\"type\":\"GET_RECORD\",\"afterSequence\":0}", "LEGACY_EVIDENCE_UNAVAILABLE"); assert(strstr(response, "\"sequence\":999"));
	/* Restriction completeness, self-account exclusion, expiry and permanent denial. */
	struct kr_policy policy; assert(kr_policy_decode(boards[2].core.current.payload, KR_POLICY_BASE, &policy));
	assert(!kr_policy_allows(&policy, b->radio.own.owner_ref, (uint64_t)epoch));
	assert(kr_policy_allows(&policy, other->radio.own.owner_ref, (uint64_t)epoch));
	assert(!kr_policy_allows(&policy, other->radio.own.owner_ref, policy.valid_until));
	uint8_t *v = boards[2].core.current.payload; kr_put16(v + 477, 1); memcpy(v + 487, other->radio.own.owner_ref, 16); v[503] = 2;
	assert(kr_policy_decode(v, KR_POLICY_BASE + 25, &policy)); assert(!kr_policy_allows(&policy, other->radio.own.owner_ref, (uint64_t)epoch));
	v[481] = 0; assert(kr_policy_decode(v, KR_POLICY_BASE + 25, &policy)); assert(!kr_policy_allows(&policy, seed, (uint64_t)epoch));
	/* Sidecar write failure cannot publish an unprovable original. */
	b->fail_key = 301; struct kr_record test = boards[1].engine.records[0]; test.kind = KR_REVIEW; test.sequence = 1000; test.own = b->engine.config.profile;
	assert(kr_product_record(&b->app, 1, &test)); assert(b->saved[1].sequence != 1000); b->fail_key = -1;
	/* Legacy v2 data remains valid; v3 does not reinterpret old reserved bytes. */
	struct kr_settings legacy = b->persisted; strcpy(legacy.format, KR_POLICY_LEGACY_FORMAT); legacy.payload[3] = '2';
	uint8_t *old_grant = legacy.payload + 200; old_grant[0] = 3; kr_put64(old_grant + 21, 1); kr_put32(legacy.payload + 483, 0);
	uint8_t signed_grant[224]; const char *old_domain = "TINGLE-PEER-GRANT-V2\n";
	memcpy(signed_grant, old_domain, strlen(old_domain)); memcpy(signed_grant + strlen(old_domain), old_grant, 197);
	crypto_ed25519_sign(old_grant + 197, server_secret, signed_grant, strlen(old_domain) + 197);
	kr_hash(legacy.payload, legacy.payload_size, digest); kr_hex(digest, 32, legacy.hash);
	assert(kr_settings_valid(&legacy) && kr_policy_decode(legacy.payload, legacy.payload_size, &policy)); assert(policy.opportunity_seconds == 7 * 86400);
	/* Zero, unsigned mismatch and excessive duration are rejected. */
	uint8_t bytes[KR_PAYLOAD_MAX]; memcpy(bytes, b->persisted.payload, KR_POLICY_BASE); kr_put32(bytes + 483, 0);
	assert(!kr_policy_decode(bytes, KR_POLICY_BASE, &policy)); kr_put32(bytes + 483, 4 * 86400); assert(!kr_policy_decode(bytes, KR_POLICY_BASE, &policy));
	/* A gauge reports a real zero, USB/no-cell and gauge errors never report 100%. */
	struct kr_status battery = {.battery_mv = 3750, .charging = -1}; struct kr_battery_reading br = kr_battery_reading(battery);
	assert(br.percent == 50 && !strcmp(br.source, "VOLTAGE_ESTIMATE")); battery.usb_valid = battery.usb_present = true;
	assert(kr_battery_reading(battery).percent == -1); battery.gauge_ready = battery.gauge_percent_valid = true;
	battery.gauge_percent = 0; br = kr_battery_reading(battery); assert(br.percent == 0 && !strcmp(br.source, "FUEL_GAUGE"));
	battery.presence_valid = true; battery.battery_present = false; assert(!strcmp(kr_battery_reading(battery).presence, "ABSENT") && kr_battery_reading(battery).percent == -1);
	battery.battery_present = true; battery.gauge_percent_valid = false; assert(kr_battery_reading(battery).percent == -1);
	/* All originals removed, reboot, then another record: cursor and high-water
	 * state survive independently of the remaining raw rows. */
	boot(2, 3, f); struct test_board *fresh = &boards[2]; uint8_t raw[] = {1, 2, 3};
	assert(kr_radio_error(&fresh->engine, 77, raw, sizeof(raw), KR_BAD_DATA, (uint64_t)epoch) == KR_OK);
	uint32_t first = fresh->engine.records[0].sequence, high = fresh->persisted_counter; uint8_t id[16]; memcpy(id, fresh->engine.records[0].id, 16);
	assert(kr_ack(&fresh->engine, id) == KR_OK && !fresh->saved[0].kind);
	struct kr_io old_io = fresh->engine.io; old_io.record = raw_record; old_io.suppression = suppression;
	struct kr_config saved_config = fresh->engine.config; uint8_t cursor[16]; memcpy(cursor, fresh->engine.cursor_epoch, 16);
	kr_init(&fresh->engine, saved_config.device, old_io); fresh->engine.config = saved_config; fresh->engine.next_sequence = high;
	memcpy(fresh->engine.cursor_epoch, cursor, 16); memcpy(fresh->engine.suppressed, fresh->suppressed, sizeof(fresh->suppressed)); assert(kr_restored_valid(&fresh->engine));
	struct kr_product_io saved_io = fresh->app.io; kr_product_init(&fresh->app, &fresh->core, &fresh->engine, saved_io, server);
	fresh->engine.io.record = record; fresh->engine.io.suppression = wrapped_suppression; fresh->engine.ready = true;
	raw[0] = 4; assert(kr_radio_error(&fresh->engine, 77, raw, sizeof(raw), KR_BAD_DATA, (uint64_t)epoch) == KR_OK);
	assert(fresh->engine.records[0].sequence > first && !memcmp(cursor, fresh->engine.cursor_epoch, 16));
	owner(fresh, NULL); request(fresh, "{\"type\":\"GET_RECORD\",\"afterSequence\":1}", "CURSOR_EPOCH_REQUIRED");
	request(fresh, "{\"type\":\"GET_RECORD\",\"afterSequence\":1,\"recordCursorEpoch\":\"00000000000000000000000000000000\"}", "CURSOR_EPOCH_MISMATCH");
	fresh->fail_counter = true; raw[0] = 5;
	assert(kr_radio_error(&fresh->engine, 77, raw, sizeof(raw), KR_BAD_DATA, (uint64_t)epoch) == KR_STORAGE && !fresh->saved[1].kind);
	/* A superseded candidate's upload only suppresses that device; it cannot
	 * block the selected device of the same account after upload/reboot. */
	FILE *scratch = tmpfile(); assert(scratch); boot(2, 3, scratch); fresh = &boards[2];
	struct kr_record duplicate = {.format = KR_VERSION, .kind = KR_REVIEW, .reason = KR_ACCOUNT_DUPLICATE,
		.phase = KR_COMPLETE, .peer = 777, .sequence = 55, .occurred_at = (uint64_t)epoch, .expires_at = (uint64_t)epoch + 100};
	duplicate.id[0] = 99; duplicate.own = fresh->engine.config.profile; fresh->engine.records[0] = duplicate;
	memcpy(fresh->app.accounts[0], boards[1].radio.own.owner_ref, 16);
	kr_peer_scope(boards[1].radio.own.certificate, &fresh->app.scopes[0].epoch, fresh->app.scopes[0].tag);
	assert(kr_ack(&fresh->engine, duplicate.id) == KR_OK && fresh->app.duplicate_suppression[0]);
	struct kr_peer_verified remote; assert(kr_peer_certificate(boards[1].radio.own.certificate, boards[1].radio.own.public_key, server, (uint64_t)epoch, &remote));
	assert(fresh->radio.allowed(fresh->radio.application, &remote, (uint64_t)epoch));
	/* Four nearby devices, two accounts; alternate arrival order to exercise
	 * competing unconfirmed choices without a queue residence timer. */
	boot(0, 1, scratch); boot(1, 2, scratch); boot(2, 1, scratch); boot(3, 2, scratch);
	for (unsigned turn = 0; turn < 450; ++turn) {
		for (unsigned step = 0; step < 4; ++step) {
			unsigned a = turn % 2 ? step : 3 - step; size_t count = kr_peer_advertisement(&boards[a].radio, &boards[a].engine, packet, &sent); assert(count);
			for (unsigned j = 0; j < 4; ++j) { if (j != a) { (void)kr_peer_receive(&boards[j].radio, &boards[j].engine, packet, count, (uint64_t)epoch); } }
			kr_sent(&boards[a].engine, sent); kr_drain(&boards[a].engine);
		}
	}
	unsigned confirmed = 0; for (unsigned i = 0; i < 4; ++i) {
		assert(kr_count(&boards[i].engine, KR_CONFIRMED) <= 1 && !boards[i].engine.storage_error);
		confirmed += kr_count(&boards[i].engine, KR_CONFIRMED);
	} assert(confirmed >= 2); test_owner_transition(scratch); test_candidate_and_completion(scratch); test_same_payload_resume(scratch); test_failed_input_change(scratch);
	test_applied_proof(f, scratch); assert(!fclose(scratch));
	assert(!fclose(f)); puts("product: account dedup + durable suppression, persistent cursor/sequence, signed dynamic duration, legacy migration, battery truth and full flow passed"); return 0;
}

#include "registration.h"
#include "settings_storage.h"
#include <errno.h>
#include "monocypher-ed25519.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Public RFC8032 test key ONLY. Never included in the firmware target. */
static const uint8_t test_seed[32] = {
	0x9d,0x61,0xb1,0x9d,0xef,0xfd,0x5a,0x60,0xba,0x84,0x4a,0xf4,0x92,0xec,0x2c,0xc4,
	0x44,0x49,0xc5,0x69,0x7b,0x32,0x69,0x19,0x70,0x3b,0xac,0x03,0x1c,0xae,0x7f,0x60};
static const char nonce[] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
static const char uuid[] = "11111111-1111-4111-8111-111111111111";
static struct kr_settings disk;
static int writes, fail_at;
static bool authorized = true;
static struct kr_peer_plan authorized_plan;

static int save(void *ctx, const struct kr_settings *next)
{
	(void)ctx;
	++writes;
	if (fail_at == 1) { return -1; } /* power cut before atomic commit */
	disk = *next;
	return fail_at == 2 ? -1 : 0; /* committed, but readback/response lost */
}

static const char *allow(void *ctx, const struct kr_settings *next,
	const struct kr_settings_auth *auth, struct kr_peer_plan *plan)
{
	(void)ctx; (void)next; (void)auth;
	if (!authorized) { return "POLICY_UNRESOLVED"; }
	*plan = authorized_plan;
	return NULL;
}

static void init(struct kr_core *core)
{
	memset(core, 0, sizeof(*core));
	strcpy(core->identity.serial, "synthetic-device");
	strcpy(core->identity.key_id, "test-key-01");
	uint8_t seed[32]; memcpy(seed, test_seed, sizeof(seed));
	crypto_ed25519_key_pair(core->identity.secret, core->identity.public_key, seed);
	core->platform = (struct kr_platform){.save = save, .authorize = allow};
	if (disk.magic) { assert(kr_settings_valid(&disk)); core->current = disk; }
}

static struct kr_settings settings(int64_t version)
{
	struct kr_settings s = {0};
	s.magic = KR_RECORD_MAGIC; s.device_id = INT64_C(9007199254740993); s.version = version;
	strcpy(s.owner_generation, "33333333-3333-4333-8333-333333333333");
	strcpy(s.sync_run, "22222222-2222-4222-8222-222222222222");
	strcpy(s.nonce, nonce); strcpy(s.format, "test-only-opaque-v1");
	memcpy(s.payload, "abc", 3); s.payload_size = 3;
	uint8_t hash[32]; kr_hash(s.payload, s.payload_size, hash); kr_hex(hash, sizeof(hash), s.hash);
	assert(!strcmp(s.hash, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
	return s;
}

struct fake_settings_flash {
	struct kr_settings latest, history, backup, repair;
	bool latest_present, history_present, backup_present, repair_present, corrupt_new, corrupt_primary_only;
	int latest_error, repair_error, fail_key, writes;
};
static int settings_read(void *ctx, uint16_t key, unsigned history, struct kr_settings *out)
{
	struct fake_settings_flash *f = ctx;
	if (key == KR_SETTINGS_REPAIR_KEY) {
		if (!f->repair_present || history) { return -ENOENT; }
		*out = f->repair; return f->repair_error ? f->repair_error : (int)sizeof(*out);
	}
	if (key == KR_SETTINGS_BACKUP_KEY) {
		if (!f->backup_present || history) { return -ENOENT; }
		*out = f->backup; return sizeof(*out);
	}
	assert(key == KR_SETTINGS_CURRENT_KEY);
	if (history) {
		if (history != 1 || !f->history_present) { return -ENOENT; }
		*out = f->history; return sizeof(*out);
	}
	if (!f->latest_present) { return -ENOENT; }
	*out = f->latest;
	return f->latest_error ? f->latest_error : (int)sizeof(*out);
}
static int settings_write(void *ctx, uint16_t key, const struct kr_settings *s)
{
	struct fake_settings_flash *f = ctx;
	++f->writes;
	if (key == f->fail_key) { return -EIO; }
	if (key == KR_SETTINGS_BACKUP_KEY) { f->backup = *s; f->backup_present = true; }
	else if (key == KR_SETTINGS_REPAIR_KEY) {
		f->repair = *s; f->repair_present = true; f->repair_error = f->corrupt_new ? -EIO : 0;
	}
	else {
		assert(key == KR_SETTINGS_CURRENT_KEY);
		f->latest = *s; f->latest_present = true; f->latest_error = 0;
		if (f->corrupt_new || f->corrupt_primary_only) { f->latest.payload[0] ^= 1; f->latest_error = -EIO; }
	}
	return 0;
}
static void storage_recovery_tests(void)
{
	struct fake_settings_flash flash = {.latest = settings(9), .history = settings(8),
		.latest_present = true, .history_present = true, .latest_error = -EIO};
	flash.latest.payload[0] ^= 1;
	const struct fake_settings_flash original = flash;
	const struct kr_settings_storage io = {.ctx = &flash, .read = settings_read, .write = settings_write};
	struct kr_core core = {0};
	assert(kr_settings_storage_load(&core, &io) == 1);
	assert(core.settings_recovery_required && core.current.version == 8 && !core.storage_error);
	assert(!memcmp(&flash, &original, sizeof(flash))); /* Read-only, damaged v9 retained. */
	flash.latest_error = 0; /* CRC can pass while payload/schema validation fails. */
	assert(kr_settings_storage_load(&core, &io) == 1 && core.current.version == 8);
	flash.history_present = false; flash.backup = settings(8); flash.backup_present = true;
	assert(kr_settings_storage_load(&core, &io) == 1 && core.current.version == 8);
	flash.latest_present = false;
	assert(kr_settings_storage_load(&core, &io) == 0 && !core.current.magic && !core.settings_recovery_required);
	flash.latest_present = true; core.reset.phase = KR_RESET_DONE;
	strcpy(core.reset.owner_generation, flash.backup.owner_generation);
	assert(kr_settings_storage_load(&core, &io) == -EILSEQ); /* No pre-transfer owner resurrection. */
	core.reset.phase = KR_RESET_NONE; flash = original;
	struct kr_settings scratch, previous = settings(8), next = settings(10);
	flash.fail_key = KR_SETTINGS_BACKUP_KEY;
	assert(kr_settings_storage_save(&io, &previous, &next, &scratch) == -EIO);
	assert(flash.writes == 1 && flash.latest.version == 9);
	flash.fail_key = 0; flash.corrupt_new = true;
	assert(kr_settings_storage_save(&io, &previous, &next, &scratch) == -EIO);
	assert(flash.backup_present && !memcmp(&flash.backup, &previous, sizeof(previous)));
	flash.history_present = false;
	assert(kr_settings_storage_load(&core, &io) == 1 && core.current.version == 8);
	flash.corrupt_new = false;
	assert(!kr_settings_storage_save(&io, &previous, &next, &scratch));
	assert(!kr_settings_storage_load(&core, &io) && core.current.version == 10 && !core.settings_recovery_required);
	/* A first sync must leave a verified backup even when current is corrupt.
	 * A cut before publishing current still cannot resurrect a tombstone. */
	memset(&flash, 0, sizeof(flash)); flash.corrupt_new = true;
	assert(kr_settings_storage_save(&io, NULL, &next, &scratch) == -EIO);
	assert(flash.backup_present && flash.backup.version == next.version);
	assert(kr_settings_storage_load(&core, &io) == 1 && core.current.version == next.version);
	flash.latest_present = false; flash.repair_present = false;
	assert(!kr_settings_storage_load(&core, &io) && !core.current.magic);
	memset(&flash, 0, sizeof(flash)); flash.fail_key = KR_SETTINGS_BACKUP_KEY;
	assert(kr_settings_storage_save(&io, NULL, &next, &scratch) == -EIO && !flash.latest_present);
	memset(&flash, 0, sizeof(flash)); flash.corrupt_primary_only = true;
	assert(!kr_settings_storage_save(&io, NULL, &next, &scratch));
	assert(flash.latest_error && flash.repair_present && !flash.repair_error);
	assert(!kr_settings_storage_load(&core, &io) && core.current.version == next.version);
	flash.latest_present = false; /* Cut before ID 10 commits, ID 12 is a verified commit. */
	assert(!kr_settings_storage_load(&core, &io) && core.current.version == next.version);
	core.reset.phase = KR_RESET_DONE; strcpy(core.reset.owner_generation, next.owner_generation);
	assert(kr_settings_storage_load(&core, &io) == -EILSEQ); /* No pre-reset slot resurrection. */
	memset(&core.reset, 0, sizeof(core.reset));
	flash.latest = settings(11); flash.latest_present = true; flash.latest_error = 0;
	assert(!kr_settings_storage_load(&core, &io) && core.current.version == 11);
	flash.latest = settings(9);
	assert(!kr_settings_storage_load(&core, &io) && core.current.version == 10);
	flash.latest = settings(10); flash.latest.owner_generation[0] = '4';
	assert(kr_settings_storage_load(&core, &io) == -EILSEQ); /* Conflicting valid owners fail closed. */

	memset(&disk, 0, sizeof(disk)); init(&core); core.current = settings(8); core.settings_recovery_required = true;
	char proof[87]; struct kr_identity key_before = core.identity;
	authorized = true; fail_at = 0; writes = 0;
	assert(!strcmp(kr_store(&core, &core.current, NULL, proof), "SETTINGS_RECOVERY_REQUIRED"));
	assert(core.settings_recovery_required && !writes);
	authorized = false;
	assert(!strcmp(kr_store(&core, &next, NULL, proof), "POLICY_UNRESOLVED") && core.settings_recovery_required);
	authorized = true;
	assert(!kr_store(&core, &next, NULL, proof) && !core.settings_recovery_required && core.current.version == 10);
	assert(!memcmp(&key_before, &core.identity, sizeof(key_before)));
	memset(&disk, 0, sizeof(disk)); writes = 0;
	puts("PASS: CRC/schema recovery quarantine, no resurrection/write, backup failure, signed resync and key preservation");
}

static void codecs(void)
{
	char text[128]; uint8_t bytes[64];
	for (size_t len = 0; len <= 64; ++len) {
		for (size_t i = 0; i < len; ++i) { bytes[i] = (uint8_t)(i * 73); }
		kr_base64_encode(bytes, len, text);
		uint8_t result[64]; assert(kr_base64_decode(text, result, sizeof(result)) == (int)len);
		assert(!memcmp(bytes, result, len));
	}
	assert(kr_base64_decode("AB", bytes, sizeof(bytes)) < 0);
	assert(kr_base64_decode("AAA=", bytes, sizeof(bytes)) < 0);
	assert(kr_base64_decode("A", bytes, sizeof(bytes)) < 0);
	assert(kr_base64_decode("AAAA", bytes, 2) < 0);
	assert(kr_nonce(nonce) && !kr_nonce("bad"));
	assert(!kr_identifier("key\nSTORED"));
	assert(kr_uuid(uuid) && !kr_uuid("11111111-1111-4111-8111-11111111111Z"));
	int64_t n;
	assert(kr_positive("9223372036854775807", &n) && n == INT64_MAX);
	const char *invalid[] = {"0", "01", "-1", "1.0", "1e2", "9223372036854775808", "", "true"};
	for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) { assert(!kr_positive(invalid[i], &n)); }
	assert(kr_epoch("2026-11-02T00:05:00Z", &n) && n == INT64_C(1793577900));
	assert(kr_epoch("2026-11-02T00:05:00.123456789Z", &n) && n == INT64_C(1793577900));
	assert(!kr_epoch("2026-02-29T00:00:00Z", &n));
	assert(!kr_epoch("2026-11-02T00:00:00.Z", &n));
	assert(!kr_epoch("2026-11-02T00:00:60Z", &n));
	assert(!kr_epoch("2026-11-02T00:00:00Zjunk", &n));
}

static void parser(void)
{
	struct kr_json obj;
	char valid[] = " { \"n\" : 9007199254740993, \"requestId\":\"한글\\n\\\"\\uD83D\\uDE00\" } ";
	assert(kr_json_parse(valid, &obj));
	assert(!strcmp(kr_json_number(&obj, "n"), "9007199254740993"));
	assert(!kr_json_string(&obj, "n"));
	char quote[224]; assert(kr_json_quote(kr_json_string(&obj, "requestId"), quote, sizeof(quote)));
	assert(strstr(quote, "\\u000a\\\""));
	const char *bad[] = {"", "{", "{\"n\":01}", "{\"n\":1.5}", "{\"n\":1e2}",
		"{\"n\":1,}", "{\"n\":1}{}", "{\"n\":1,\"\\u006e\":2}", "{\"x\":\"\\u0000\"}",
		"{\"x\":\"\\uD800\"}", "{\"x\":\"\\uDC00\"}", "{\"x\":\"\\u123\"}",
		"{\"x\":\"\\q\"}", "{\"x\":\"\xc0\x80\"}", "{\"n\":true}", "{\"n\":{}}"};
	for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); ++i) {
		char buf[128]; strcpy(buf, bad[i]); assert(!kr_json_parse(buf, &obj));
	}
	/* Deterministic malformed input smoke, including all truncated prefixes. */
	const char *input = "{\"requestId\":\"\\uD83D\\uDE00\",\"n\":1234}";
	for (size_t i = 0; i < strlen(input); ++i) {
		char buf[128]; memcpy(buf, input, i); buf[i] = 0; assert(!kr_json_parse(buf, &obj));
	}
	unsigned random = 12345;
	for (int i = 0; i < 20000; ++i) {
		char buf[128];
		for (size_t j = 0; j < sizeof(buf) - 1; ++j) {
			random = random * 1664525u + 1013904223u; buf[j] = (char)(random >> 24);
		}
		buf[sizeof(buf) - 1] = 0; (void)kr_json_parse(buf, &obj);
	}
}

static void persistence(void)
{
	struct kr_core core; init(&core);
	struct kr_settings s = settings(12); char proof[87], previous[87];
	assert(!kr_store(&core, &s, NULL, proof)); assert(writes == 1 && core.current.version == 12);
	strcpy(previous, proof);
	init(&core); assert(!kr_store(&core, &s, NULL, proof));
	assert(writes == 1 && !strcmp(previous, proof)); /* reconnect/reboot/lost ACK */
	struct kr_settings changed = s; changed.version = 11;
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "STALE_SETTINGS"));
	changed = s; changed.owner_generation[0] = '4';
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "OWNER_MISMATCH"));
	changed = s; changed.device_id++;
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "OWNER_MISMATCH"));
	changed = s; strcpy(changed.format, "another-format");
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "VERSION_CONFLICT"));
	changed = s; changed.payload[0] ^= 1;
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "INVALID_SETTINGS"));
	uint8_t hash[32]; kr_hash(changed.payload, changed.payload_size, hash); kr_hex(hash, 32, changed.hash);
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "VERSION_CONFLICT"));
	changed = s; changed.version = 13; authorized = false;
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "POLICY_UNRESOLVED"));
	assert(core.current.version == 12 && writes == 1);
	authorized = true; fail_at = 1;
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "STORAGE_ERROR"));
	assert(core.current.version == 12 && disk.version == 12);
	init(&core); fail_at = 2;
	assert(!strcmp(kr_store(&core, &changed, NULL, proof), "STORAGE_ERROR"));
	assert(core.current.version == 12 && disk.version == 13);
	init(&core); fail_at = 0; int count = writes;
	assert(core.current.version == 13 && !kr_store(&core, &changed, NULL, proof) && writes == count);
	disk.payload[0] ^= 1; assert(!kr_settings_valid(&disk)); disk.payload[0] ^= 1;
}

static void framing(void)
{
	static struct kr_frame frame;
	const char message[] = "{\"protocolVersion\":1,\"type\":\"GET_STATUS\",\"requestId\":\"r\"}\n";
	for (size_t chunk = 1; chunk <= 20; ++chunk) {
		kr_frame_reset(&frame);
		for (size_t offset = 0; offset < strlen(message);) {
			size_t count = strlen(message) - offset;
			if (count > chunk) { count = chunk; }
			int result = kr_frame_push(&frame, message + offset, count);
			offset += count;
			assert(result == (offset == strlen(message) ? 1 : 0));
		}
		assert(!strncmp(frame.bytes, message, strlen(message) - 1));
	}
	for (size_t cut = 1; cut < strlen(message); ++cut) {
		kr_frame_reset(&frame);
		for (size_t i = 0; i < cut; ++i) { assert(!kr_frame_push(&frame, message + i, 1)); }
		kr_frame_reset(&frame); /* disconnect */
		for (size_t i = 0; i < strlen(message); ++i) {
			assert(kr_frame_push(&frame, message + i, 1) == (i == strlen(message) - 1 ? 1 : 0));
		}
	}
	kr_frame_reset(&frame); assert(kr_frame_push(&frame, "{}\n{}", 5) < 0);
	assert(kr_frame_push(&frame, "\0", 1) < 0);
	for (size_t i = 0; i < KR_FRAME_MAX - 1; ++i) { assert(!kr_frame_push(&frame, " ", 1)); }
	assert(kr_frame_push(&frame, "\n", 1) == 1);
	assert(kr_frame_push(&frame, "x", 1) < 0);
	kr_frame_reset(&frame); assert(kr_frame_push(&frame, message, 21) < 0);
}

static void request_tests(void)
{
	struct kr_core core; init(&core);
	char out[KR_RESPONSE_MAX], frame[KR_FRAME_MAX + 1];
	struct kr_status status = {.battery_mv = 3900, .charging = 1};
	strcpy(frame, "{\"protocolVersion\":1,\"type\":\"GET_DEVICE_INFO\",\"requestId\":\"a\\\"b\"}");
	kr_request(&core, frame, status, true, out);
	assert(strstr(out, "\"type\":\"DEVICE_INFO\"") && strstr(out, "a\\\"b"));
	strcpy(frame, "{\"protocolVersion\":1,\"type\":\"GET_STATUS\",\"requestId\":\"s\"}");
	kr_request(&core, frame, status, true, out);
	assert(strstr(out, "\"charging\":true") && strstr(out, "\"batteryPercent\":null") && strlen(out) <= 512);
	snprintf(frame, sizeof(frame), "{\"protocolVersion\":1,\"type\":\"GET_REGISTRATION_PROOF\","
		"\"requestId\":\"r\",\"challengeId\":\"%s\",\"nonce\":\"%s\","
		"\"expiresAt\":\"2026-11-02T00:05:00Z\",\"proofProtocolVersion\":\"%s\"}", uuid, nonce, KR_PROOF_VERSION);
	char duplicate[KR_FRAME_MAX + 1]; strcpy(duplicate, frame);
	kr_request(&core, frame, status, true, out); assert(strstr(out, "\"type\":\"REGISTRATION_PROOF\""));
	kr_request(&core, duplicate, status, false, out); assert(strstr(out, "REGISTRATION_WINDOW_CLOSED"));
	struct kr_settings s = settings(14);
	snprintf(frame, sizeof(frame), "{\"protocolVersion\":1,\"type\":\"SYNC_SETTINGS\",\"requestId\":\"s\","
		"\"deviceId\":9007199254740993,\"settingsVersion\":14,\"syncRunId\":\"%s\","
		"\"ownerGeneration\":\"%s\",\"nonce\":\"%s\",\"payloadHash\":\"%s\","
		"\"payloadFormat\":\"%s\",\"payloadEncoding\":\"base64url\",\"payload\":\"YWJj\","
		"\"keyId\":\"test-key-01\",\"proofProtocolVersion\":\"%s\"}",
		s.sync_run, s.owner_generation, nonce, s.hash, s.format, KR_PROOF_VERSION);
	strcpy(duplicate, frame); authorized = false;
	kr_request(&core, frame, status, true, out);
	assert(strstr(out, "POLICY_UNRESOLVED") && core.current.version == 13);
	struct kr_peer_plan plan;
	assert(!core.stop_peer && !kr_peer_after_response(&core, true, &plan));
	authorized_plan = (struct kr_peer_plan){
		.verified_time = 1793577900, .length = 17, .service_data = {1}
	};
	authorized = true; kr_request(&core, duplicate, status, true, out);
	assert(strstr(out, "\"stored\":true,\"applied\":false,\"timeReady\":false"));
	assert(core.current.version == 14 && core.current.device_id == INT64_C(9007199254740993));
	assert(core.stop_peer && kr_peer_after_response(&core, true, &plan));
	assert(plan.length == 17 && plan.verified_time == 1793577900 && plan.service_data[0] == 1);
	assert(!kr_peer_after_response(&core, true, &plan));

	init(&core); /* The persisted setting does not restore a verified clock. */
	assert(!kr_peer_after_response(&core, true, &plan));
	char proof[87];
	assert(!kr_store(&core, &s, NULL, proof));
	assert(!kr_peer_after_response(&core, false, &plan)); /* Notify failed */
	assert(!kr_peer_after_response(&core, true, &plan));
	assert(!kr_store(&core, &s, NULL, proof) && kr_peer_after_response(&core, true, &plan));

	s.version = 15;
	authorized_plan.verified_time = 0;
	assert(!strcmp(kr_store(&core, &s, NULL, proof), "INVALID_SETTINGS"));
	assert(!core.stop_peer && !kr_peer_after_response(&core, true, &plan));
	authorized_plan.verified_time = 1793577900;
	fail_at = 1;
	assert(!strcmp(kr_store(&core, &s, NULL, proof), "STORAGE_ERROR"));
	assert(core.stop_peer && !kr_peer_after_response(&core, true, &plan));
	assert(disk.version == 14);
	fail_at = 0;

	init(&core);
	authorized_plan.length = 0; /* Matching OFF */
	assert(!kr_store(&core, &s, NULL, proof));
	assert(core.stop_peer && !kr_peer_after_response(&core, true, &plan));
	authorized_plan = (struct kr_peer_plan){0};
}

static void server_proof_tests(void)
{
	struct kr_core core; init(&core);
	struct kr_settings s = settings(16);
	strcpy(s.format, KR_DEV_PAYLOAD_FORMAT);
	s.payload[0] = 1;
	s.payload_size = 1;
	uint8_t digest[32];
	kr_hash(s.payload, s.payload_size, digest);
	kr_hex(digest, sizeof(digest), s.hash);
	struct kr_settings_nonce pending;
	uint8_t nonce_bytes[32];
	memset(nonce_bytes, 1, sizeof(nonce_bytes));
	kr_issue_settings_nonce(&pending, nonce_bytes, 1000);
	const char server_key_id[] = "dev-ed25519-d75a980182b10ab7";
	/* Independent Node/OpenSSL vector, generated with the public RFC8032 fixture key. */
	const struct kr_settings_auth auth = {
		.protocol_version = KR_SETTINGS_PROOF_VERSION, .server_key_id = server_key_id,
		.device_nonce = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
		.server_time = "2026-11-02T00:05:00.000Z",
		.signature = "For4CM5G37aQcXNKSDMuJ4mGCTJNnjgfBs-NjMX90UJ0jZHtg_aexjL8mkSVMyry04KUm_BLuSgM2WlZS-1EDQ",
	};
	int64_t verified_time;
	const uint8_t *server_key = core.identity.public_key;
	assert(!strcmp(kr_verify_dev_settings(&core.identity, &s, &auth, &pending, 61000,
		server_key_id, server_key, &verified_time), "SYNC_CHALLENGE_REQUIRED"));
	struct kr_settings_auth wrong_time = auth;
	wrong_time.server_time = "2026-11-02T00:05:01.000Z";
	assert(!strcmp(kr_verify_dev_settings(&core.identity, &s, &wrong_time, &pending, 2000,
		server_key_id, server_key, &verified_time), "INVALID_SERVER_PROOF"));
	++s.device_id;
	assert(!strcmp(kr_verify_dev_settings(&core.identity, &s, &auth, &pending, 2000,
		server_key_id, server_key, &verified_time), "INVALID_SERVER_PROOF"));
	--s.device_id;
	s.owner_generation[0] = '4';
	assert(!strcmp(kr_verify_dev_settings(&core.identity, &s, &auth, &pending, 2000,
		server_key_id, server_key, &verified_time), "INVALID_SERVER_PROOF"));
	s.owner_generation[0] = '3';
	s.payload[0] = 0;
	kr_hash(s.payload, s.payload_size, digest);
	kr_hex(digest, sizeof(digest), s.hash);
	assert(!strcmp(kr_verify_dev_settings(&core.identity, &s, &auth, &pending, 2000,
		server_key_id, server_key, &verified_time), "INVALID_SERVER_PROOF"));
	s.payload[0] = 1;
	kr_hash(s.payload, s.payload_size, digest);
	kr_hex(digest, sizeof(digest), s.hash);
	assert(!kr_verify_dev_settings(&core.identity, &s, &auth, &pending, 60999,
		server_key_id, server_key, &verified_time));
	assert(verified_time == INT64_C(1793577900));
	assert(!strcmp(kr_verify_dev_settings(&core.identity, &s, &auth, &pending, 60999,
		server_key_id, server_key, &verified_time), "SYNC_CHALLENGE_REQUIRED"));
}

int main(int argc, char **argv)
{
	assert(argc == 2);
	codecs(); parser(); framing(); storage_recovery_tests(); persistence(); request_tests(); server_proof_tests();
	memset(&disk, 0, sizeof(disk)); struct kr_core core; init(&core);
	struct kr_settings s = settings(INT64_MAX); char registration[87], stored[87], key[44];
	assert(!kr_registration_proof(&core.identity, uuid, nonce, INT64_C(1793577900), registration));
	assert(!kr_store(&core, &s, NULL, stored));
	kr_base64_encode(core.identity.public_key, 32, key);
	uint8_t signature[64]; char hex[129];
	crypto_ed25519_sign(signature, core.identity.secret, (const uint8_t *)"", 0);
	kr_hex(signature, sizeof(signature), hex);
	assert(!strcmp(hex, "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
		"5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"));
	FILE *file = fopen(argv[1], "wb"); assert(file);
	fprintf(file, "publicKey=%s\nregistration=%s\nstored=%s\nhash=%s\n", key, registration, stored, s.hash);
	assert(!fclose(file));
	puts("PASS: codecs, strict JSON, RFC8032, persistence/replay/failure, request integration, server proof");
	return 0;
}

#include "registration.h"
#include "profile.h"
#include "monocypher-ed25519.h"
#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Public RFC8032 fixture only; not linked into the firmware target. */
static const uint8_t seed_fixture[32] = {
	0x9d,0x61,0xb1,0x9d,0xef,0xfd,0x5a,0x60,0xba,0x84,0x4a,0xf4,0x92,0xec,0x2c,0xc4,
	0x44,0x49,0xc5,0x69,0x7b,0x32,0x69,0x19,0x70,0x3b,0xac,0x03,0x1c,0xae,0x7f,0x60
};
static char server_id[65];
static uint8_t server_secret[64], server_public[32];
static const char nonce[] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
static struct kr_identity identity;
static struct kr_settings disk;
static struct kr_settings_nonce challenge;
static int writes, failure;
static char server_signature[87];

static int save(void *ctx, const struct kr_settings *next)
{
	(void)ctx;
	++writes;
	if (failure == 1) { return -1; }
	disk = *next;
	return failure == 2 ? -1 : 0;
}

static const char *authorize(void *ctx, const struct kr_settings *next,
	const struct kr_settings_auth *auth, struct kr_peer_plan *plan)
{
	const struct kr_core *core = ctx;
	const char *error = kr_verify_dev_settings(&core->identity, next, auth,
		&challenge, 2000, server_id, server_public, &plan->verified_time);
	if (error) { return error; }
	struct kr_profile_data view;
	bool enabled = next->payload[0] != 0;
	if (!strcmp(next->format, KR_PROFILE_STORAGE_FORMAT)) {
		assert(kr_profile_data_decode(next->payload, next->payload_size, &view));
		enabled = (view.options & 1u) != 0;
	}
	plan->length = enabled ? 16 : 0;
	return NULL;
}

static void init(struct kr_core *core)
{
	memset(core, 0, sizeof(*core));
	core->identity = identity;
	core->profile_transport_protected = true; /* Models the encrypted profile characteristic. */
	core->platform = (struct kr_platform){.ctx = core, .save = save, .authorize = authorize};
	if (disk.magic) { assert(kr_settings_valid(&disk)); core->current = disk; }
}

static void hash(struct kr_settings *s)
{
	uint8_t digest[32];
	kr_hash(s->payload, s->payload_size, digest);
	kr_hex(digest, sizeof(digest), s->hash);
}

static struct kr_settings load(const char *folder, const char *name, int64_t version)
{
	char path[2048];
	assert(snprintf(path, sizeof(path), "%s/%s", folder, name) < (int)sizeof(path));
	FILE *input = fopen(path, "rb"); assert(input);
	struct kr_settings s = {.magic = KR_RECORD_MAGIC, .device_id = INT64_C(9007199254740993), .version = version};
	s.payload_size = (uint32_t)fread(s.payload, 1, sizeof(s.payload), input);
	assert(feof(input) && !ferror(input) && !fclose(input));
	strcpy(s.owner_generation, "33333333-3333-4333-8333-333333333333");
	strcpy(s.sync_run, "22222222-2222-4222-8222-222222222222");
	strcpy(s.nonce, nonce); strcpy(s.format, KR_PROFILE_STORAGE_FORMAT);
	hash(&s);
	assert(kr_settings_valid(&s));
	return s;
}

static struct kr_settings_auth sign(const struct kr_settings *s, bool v2, int64_t epoch)
{
	uint8_t bytes[32]; memset(bytes, 1, sizeof(bytes));
	kr_issue_settings_nonce(&challenge, bytes, 1000);
	char text[768];
	int n = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n"
		"%" PRId64 "\n%s\n%s\n%s\n%s\n1793577900\n",
		v2 ? KR_SETTINGS_PROOF_VERSION_2 : KR_SETTINGS_PROOF_VERSION,
		server_id, identity.serial, identity.key_id, s->device_id, s->owner_generation,
		s->sync_run, s->version, s->format, s->hash, s->nonce, challenge.value);
	if (v2) { n += snprintf(text + n, sizeof(text) - (size_t)n, "%" PRId64 "\n", epoch); }
	assert(n > 0 && n < (int)sizeof(text));
	uint8_t signature[64];
	crypto_ed25519_sign(signature, server_secret, (const uint8_t *)text, (size_t)n);
	kr_base64_encode(signature, sizeof(signature), server_signature);
	return (struct kr_settings_auth){.protocol_version = v2 ? KR_SETTINGS_PROOF_VERSION_2 : KR_SETTINGS_PROOF_VERSION,
		.server_key_id = server_id, .device_nonce = challenge.value, .server_time = "2026-11-02T00:05:00Z",
		.signature = server_signature, .version2 = v2, .ownership_epoch = epoch};
}

static size_t group(const uint8_t *payload, uint8_t tag)
{
	for (size_t p = 4; p + 3 <= KR_PROFILE_STORAGE_BYTES; p += 3 + payload[p + 1]) {
		if (payload[p] == tag) { return p + 3; }
	}
	abort();
}

static void decoder(const struct kr_settings *s)
{
	struct kr_profile_data view;
	assert(kr_profile_data_decode(s->payload, s->payload_size, &view));
	assert(view.options == 7 && view.traits[0] == 0x35 && view.traits[1] == 0x0c);
	assert(view.personal[0] == 27 && view.personal[2] == 1 && view.personal[3] == 2);
	for (size_t cut = 0; cut < s->payload_size; ++cut) {
		assert(!kr_profile_data_decode(s->payload, cut, &view));
	}
	const struct { size_t offset; uint8_t value; } bad[] = {
		{0,0}, {4,0x10}, {5,47}, {7,2}, {21,0},
		{group(s->payload,0x10)+1,0xff}, {group(s->payload,0x11),6},
		{group(s->payload,0x11)+10,31}, {group(s->payload,0x11)+38,0xc0},
		{group(s->payload,0x20),1}, {group(s->payload,0x30),4},
		{group(s->payload,0x31)+1,255}, {group(s->payload,0x32),2},
		{group(s->payload,0x40)+2,3}, {group(s->payload,0x40)+9,1},
		{group(s->payload,0x40)+14,20}, {group(s->payload,0x41),8}
	};
	for (unsigned i = 0; i < sizeof(bad) / sizeof(bad[0]); ++i) {
		struct kr_settings corrupt = *s;
		corrupt.payload[bad[i].offset] = bad[i].value;
		hash(&corrupt); /* Rehashed invalid schema is still invalid. */
		assert(!kr_settings_valid(&corrupt));
	}
}

static void request(struct kr_core *core, const struct kr_settings *s,
	const struct kr_settings_auth *auth, char reply[KR_RESPONSE_MAX])
{
	char payload[KR_PAYLOAD_MAX * 2], json[KR_FRAME_MAX + 1];
	kr_base64_encode(s->payload, s->payload_size, payload);
	int length = snprintf(json, sizeof(json),
		"{\"protocolVersion\":1,\"type\":\"SYNC_SETTINGS\",\"requestId\":\"profile-test\","
		"\"proofProtocolVersion\":\"%s\",\"keyId\":\"%s\",\"deviceId\":%" PRId64 ","
		"\"settingsVersion\":%" PRId64 ",\"ownerGeneration\":\"%s\",\"syncRunId\":\"%s\","
		"\"nonce\":\"%s\",\"payloadHash\":\"%s\",\"payloadFormat\":\"%s\","
		"\"payloadEncoding\":\"base64url\",\"payload\":\"%s\",\"serverProofProtocolVersion\":\"%s\","
		"\"serverKeyId\":\"%s\",\"deviceNonce\":\"%s\",\"serverTime\":\"%s\",\"serverSignature\":\"%s\"}\n",
		KR_PROOF_VERSION, identity.key_id, s->device_id, s->version, s->owner_generation,
		s->sync_run, s->nonce, s->hash, s->format, payload, auth->protocol_version,
		auth->server_key_id, auth->device_nonce, auth->server_time, auth->signature);
	assert(length > 0 && length < (int)sizeof(json));
	struct kr_frame frame = {0};
	for (int i = 0; i < length;) {
		int count = length - i > 20 ? 20 : length - i;
		assert(kr_frame_push(&frame, json + i, (size_t)count) == (i + count == length ? 1 : 0));
		i += count;
	}
	kr_request(core, frame.bytes, (struct kr_status){.battery_mv = -1, .charging = -1}, false, reply);
}

int main(int argc, char **argv)
{
	assert(argc == 3 || argc == 4);
	strcpy(identity.serial, "synthetic-device"); strcpy(identity.key_id, "test-key-01");
	uint8_t seed[32]; memcpy(seed, seed_fixture, 32);
	crypto_ed25519_key_pair(identity.secret, identity.public_key, seed);
	/* A distinct public synthetic server key detects device/server-key confusion. */
	for (unsigned i = 0; i < 32; ++i) { seed[i] = (uint8_t)i; }
	crypto_ed25519_key_pair(server_secret, server_public, seed);
	strcpy(server_id, "dev-ed25519-"); kr_hex(server_public, 8, server_id + 12);
	struct kr_core core; init(&core);
	if (argc == 4) {
		assert(!strcmp(argv[1], "--store"));
		struct kr_settings sample = load(argv[2], argv[3], 10);
		struct kr_settings_auth grant = sign(&sample, false, 0);
		char signature[87];
		assert(!kr_store(&core, &sample, &grant, signature));
		assert(!memcmp(&disk, &sample, sizeof(sample)));
		init(&core);
		assert(!memcmp(&core.current, &sample, sizeof(sample)));
		assert(!memcmp(&core.identity, &identity, sizeof(identity)));
		puts("PASS: external synthetic profile signature, atomic storage and restored bytes");
		return 0;
	}
	struct kr_settings normal = load(argv[1], "normal.bin", 10);
	decoder(&normal);
	char stored[87], reply[KR_RESPONSE_MAX];
	struct kr_settings_auth auth = sign(&normal, false, 0);
	core.profile_transport_protected = false;
	assert(!strcmp(kr_store(&core, &normal, &auth, stored), "SECURE_TRANSPORT_REQUIRED") && writes == 0);
	core.profile_transport_protected = true;
	server_signature[0] ^= 1;
	assert(!strcmp(kr_store(&core, &normal, &auth, stored), "INVALID_SERVER_PROOF") && writes == 0);
	auth = sign(&normal, false, 0);
	assert(!kr_store(&core, &normal, &auth, stored));
	assert(writes == 1 && !memcmp(&disk, &normal, sizeof(normal)));
	char encoded[400], public_key[44], server_key[44];
	kr_base64_encode(normal.payload, normal.payload_size, encoded);
	kr_base64_encode(identity.public_key, 32, public_key);
	kr_base64_encode(server_public, 32, server_key);
	FILE *vectors = fopen(argv[2], "wb"); assert(vectors);
	fprintf(vectors, "publicKey=%s\nserverPublicKey=%s\nserverKeyId=%s\nhash=%s\npayload=%s\nserverSignature=%s\nstored=%s\n",
		public_key, server_key, server_id, normal.hash, encoded, auth.signature, stored);
	assert(!fclose(vectors));
	struct kr_peer_plan plan;
	assert(kr_peer_after_response(&core, true, &plan) && plan.length == 16);
	assert(!kr_peer_after_response(&core, true, &plan));
	init(&core);
	assert(!memcmp(core.current.payload, normal.payload, normal.payload_size));
	assert(!memcmp(&core.identity, &identity, sizeof(identity)));
	auth = sign(&normal, false, 0); request(&core, &normal, &auth, reply);
	assert(strstr(reply, "\"stored\":true") && strstr(reply, "\"applied\":false") && writes == 1);
	char info[] = "{\"protocolVersion\":1,\"type\":\"GET_DEVICE_INFO\",\"requestId\":\"i\"}";
	kr_request(&core, info, (struct kr_status){.battery_mv = -1, .charging = -1}, false, reply);
	assert(strstr(reply, "\"profileStored\":true") && strstr(reply, "\"storedPayloadBytes\":196"));
	assert(strstr(reply, KR_PROFILE_STORAGE_FORMAT) && !strstr(reply, encoded));
	const char *names[] = {"unknown.bin", "strict-age-off.bin"};
	for (unsigned i = 0; i < 2; ++i) {
		struct kr_settings next = load(argv[1], names[i], 11 + i);
		auth = sign(&next, false, 0); assert(!kr_store(&core, &next, &auth, stored));
		assert(!memcmp(disk.payload, next.payload, next.payload_size));
		bool started = kr_peer_after_response(&core, true, &plan);
		assert(started == (i == 0));
		if (i == 1) { assert(core.stop_peer); }
		init(&core); assert(core.current.version == next.version);
	}
	struct kr_settings next = normal; next.version = 20;
	failure = 1; auth = sign(&next, false, 0);
	assert(!strcmp(kr_store(&core, &next, &auth, stored), "STORAGE_ERROR"));
	assert(disk.version == 12); init(&core);
	failure = 2; auth = sign(&next, false, 0);
	assert(!strcmp(kr_store(&core, &next, &auth, stored), "STORAGE_ERROR"));
	assert(core.current.version == 12 && disk.version == 20); init(&core);
	failure = 0; int previous_writes = writes;
	auth = sign(&next, false, 0); assert(!kr_store(&core, &next, &auth, stored));
	assert(writes == previous_writes && !memcmp(&core.identity, &identity, sizeof(identity)));
	struct kr_settings stale = normal;
	auth = sign(&stale, false, 0); assert(!strcmp(kr_store(&core, &stale, &auth, stored), "STALE_SETTINGS"));
	memset(&disk, 0, sizeof(disk)); init(&core);
	core.reset.phase = KR_RESET_DONE; core.reset.ownership_epoch = 1;
	strcpy(core.reset.owner_generation, "44444444-4444-4444-8444-444444444444");
	auth = sign(&normal, false, 0);
	assert(!strcmp(kr_store(&core, &normal, &auth, stored), "PROTOCOL_UPGRADE_REQUIRED"));
	auth = sign(&normal, true, 0);
	assert(!strcmp(kr_store(&core, &normal, &auth, stored), "OWNERSHIP_EPOCH_MISMATCH"));
	auth = sign(&normal, true, 1); assert(!kr_store(&core, &normal, &auth, stored));
	assert(!memcmp(core.current.payload, normal.payload, normal.payload_size));
	puts("PASS: profile schema, signed NUS storage, key-preserving restart, atomic failures, OFF and epoch v2");
	return 0;
}

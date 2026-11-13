#include "registration.h"
#include "peer.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* Entirely synthetic keys, never included in firmware CMake sources. */
void peer_fixture_boot(struct kr_peer_material *out, unsigned index, const uint8_t payload[196], uint8_t server[32], unsigned boot)
{
	uint8_t server_seed[32], seed[32], server_secret[64];
	for (unsigned i = 0; i < 32; ++i) { server_seed[i] = (uint8_t)i; seed[i] = (uint8_t)(index + 1); }
	crypto_ed25519_key_pair(server_secret, server, server_seed);
	struct kr_identity id = {0}; crypto_ed25519_key_pair(id.secret, id.public_key, seed);
	uint8_t hardware[8] = {0}; hardware[0] = (uint8_t)(index + 1);
	strcpy(id.serial, "XIAO-"); kr_hex(hardware, 8, id.serial + 5);
	strcpy(id.key_id, "ed25519-"); kr_hex(id.public_key, 8, id.key_id + 8);
	struct kr_settings s = {.magic = KR_RECORD_MAGIC, .device_id = 100 + index, .version = 10, .payload_size = 196};
	memcpy(s.payload, payload, 196); strcpy(s.format, KR_PROFILE_STORAGE_FORMAT);
	/* Alternate actual/desired gender, same valid adult age range. */
	s.payload[178] = (index % 2) ? 2 : 1; s.payload[179] = (index % 2) ? 1 : 2;
	snprintf(s.owner_generation, sizeof(s.owner_generation), "%08x-3333-4333-8333-333333333333", index + 1);
	strcpy(s.sync_run, "22222222-2222-4222-8222-222222222222");
	strcpy(s.nonce, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	uint8_t digest[32]; kr_hash(s.payload, s.payload_size, digest); kr_hex(digest, 32, s.hash);
	char key_id[65] = "dev-ed25519-"; kr_hex(server, 8, key_id + 12);
	char text[768], signature[87]; int n = snprintf(text, sizeof(text),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n%" PRId64
		"\n%s\n%s\n%s\n%s\n1793577900\n1\n", KR_SETTINGS_PROOF_VERSION_2, key_id, id.serial,
		id.key_id, s.device_id, s.owner_generation, s.sync_run, s.version, s.format, s.hash, s.nonce, s.nonce);
	uint8_t signed_bytes[64]; crypto_ed25519_sign(signed_bytes, server_secret, (const uint8_t *)text, (size_t)n);
	kr_base64_encode(signed_bytes, 64, signature);
	struct kr_settings_auth auth = {.version2 = true, .ownership_epoch = 1,
		.protocol_version = KR_SETTINGS_PROOF_VERSION_2, .server_key_id = key_id, .signature = signature,
		.device_nonce = s.nonce, .server_time = "2026-11-02T00:05:00Z"};
	memset(seed, (int)(index + 40 + 32 * boot), sizeof(seed));
	assert(kr_peer_material_make(out, &id, &s, &auth, seed));
	crypto_wipe(id.secret, sizeof(id.secret)); crypto_wipe(server_secret, sizeof(server_secret));
}
void peer_fixture(struct kr_peer_material *out, unsigned index, const uint8_t payload[196], uint8_t server[32])
{
	peer_fixture_boot(out, index, payload, server, 0);
}

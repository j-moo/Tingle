#include "registration.h"
#include "profile.h"
#include "monocypher-ed25519.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

void kr_issue_settings_nonce(struct kr_settings_nonce *challenge, const uint8_t bytes[32], int64_t now_ms)
{
	kr_base64_encode(bytes, 32, challenge->value);
	challenge->deadline_ms = now_ms + KR_SETTINGS_CHALLENGE_SECONDS * 1000;
}

const char *kr_verify_dev_settings(const struct kr_identity *id, const struct kr_settings *next,
	const struct kr_settings_auth *auth, struct kr_settings_nonce *challenge, int64_t now_ms,
	const char *server_key_id, const uint8_t server_key[32], int64_t *verified_time)
{
	struct kr_profile_data profile;
	bool supported = !strcmp(next->format, KR_DEV_PAYLOAD_FORMAT) ?
		next->payload_size == 1 && !(next->payload[0] & ~1u) :
		!strcmp(next->format, KR_PROFILE_STORAGE_FORMAT) &&
		kr_profile_data_decode(next->payload, next->payload_size, &profile);
	if (!supported) {
		return "UNSUPPORTED_SETTINGS_FORMAT";
	}
	const char *version = auth && auth->version2 ? KR_SETTINGS_PROOF_VERSION_2 : KR_SETTINGS_PROOF_VERSION;
	if (!auth || !auth->protocol_version || strcmp(auth->protocol_version, version) ||
		!auth->server_key_id || strcmp(auth->server_key_id, server_key_id)) {
		return "INVALID_SERVER_PROOF";
	}
	if (!challenge->deadline_ms || now_ms >= challenge->deadline_ms ||
		!kr_nonce(auth->device_nonce) || strcmp(auth->device_nonce, challenge->value)) {
		return "SYNC_CHALLENGE_REQUIRED";
	}
	int64_t epoch;
	uint8_t signature[64];
	if (!kr_epoch(auth->server_time, &epoch) ||
		kr_base64_decode(auth->signature, signature, sizeof(signature)) != (int)sizeof(signature)) {
		return "INVALID_SERVER_PROOF";
	}
	char transcript[768];
	int size = snprintf(transcript, sizeof(transcript),
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRId64 "\n%s\n%s\n"
		"%" PRId64 "\n%s\n%s\n%s\n%s\n%" PRId64 "\n",
		version, server_key_id, id->serial, id->key_id,
		next->device_id, next->owner_generation, next->sync_run, next->version,
		next->format, next->hash, next->nonce, auth->device_nonce, epoch);
	if (auth->version2 && size >= 0 && size < (int)sizeof(transcript)) {
		size += snprintf(transcript + size, sizeof(transcript) - (size_t)size,
			"%" PRId64 "\n", auth->ownership_epoch);
	}
	if (size < 0 || (size_t)size >= sizeof(transcript) ||
		crypto_ed25519_check(signature, server_key, (const uint8_t *)transcript, (size_t)size)) {
		return "INVALID_SERVER_PROOF";
	}
	challenge->deadline_ms = 0;
	*verified_time = epoch;
	return NULL;
}

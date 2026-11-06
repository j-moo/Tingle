#include "registration.h"
#include "profile.h"
#include "monocypher.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* Called by one application worker, never from a Bluetooth callback. */
static struct kr_settings candidate;
void kr_request_forget_candidate(void) { crypto_wipe(&candidate, sizeof(candidate)); }

static bool equal(const char *a, const char *b) { return a && !strcmp(a, b); }

static bool field_copy(const struct kr_json *json, const char *key, char *out, size_t capacity)
{
	const char *value = kr_json_string(json, key);
	if (!value || strlen(value) >= capacity) { return false; }
	strcpy(out, value);
	return true;
}

static const char *settings(struct kr_core *core, const struct kr_json *json, char signature[87])
{
	memset(&candidate, 0, sizeof(candidate));
	candidate.magic = KR_RECORD_MAGIC;
	bool version2 = equal(kr_json_string(json, "proofProtocolVersion"), KR_PROOF_VERSION_2);
	int64_t ownership_epoch = 0;
	if (!equal(kr_json_string(json, "payloadEncoding"), "base64url") ||
		(!version2 && !equal(kr_json_string(json, "proofProtocolVersion"), KR_PROOF_VERSION)) ||
		(version2 && !kr_nonnegative(kr_json_number(json, "ownershipEpoch"), &ownership_epoch)) ||
		!equal(kr_json_string(json, "keyId"), core->identity.key_id) ||
		!kr_positive(kr_json_number(json, "deviceId"), &candidate.device_id) ||
		!kr_positive(kr_json_number(json, "settingsVersion"), &candidate.version) ||
		!field_copy(json, "ownerGeneration", candidate.owner_generation, sizeof(candidate.owner_generation)) ||
		!field_copy(json, "syncRunId", candidate.sync_run, sizeof(candidate.sync_run)) ||
		!field_copy(json, "nonce", candidate.nonce, sizeof(candidate.nonce)) ||
		!field_copy(json, "payloadHash", candidate.hash, sizeof(candidate.hash)) ||
		!field_copy(json, "payloadFormat", candidate.format, sizeof(candidate.format))) {
		return "INVALID_SETTINGS";
	}
	int decoded = kr_base64_decode(kr_json_string(json, "payload"), candidate.payload, KR_PAYLOAD_MAX);
	if (decoded <= 0) { return "INVALID_PAYLOAD"; }
	candidate.payload_size = (uint32_t)decoded;
	const struct kr_settings_auth auth = {
		.protocol_version = kr_json_string(json, "serverProofProtocolVersion"),
		.server_key_id = kr_json_string(json, "serverKeyId"),
		.device_nonce = kr_json_string(json, "deviceNonce"),
		.server_time = kr_json_string(json, "serverTime"),
		.signature = kr_json_string(json, "serverSignature"),
		.version2 = version2, .ownership_epoch = ownership_epoch,
	};
	return kr_store(core, &candidate, &auth, signature);
}

static void reset_result(const struct kr_core *core, const char *request, char response[KR_RESPONSE_MAX])
{
	const struct kr_reset_record *r = &core->reset;
	if (r->phase == KR_RESETTING) {
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"RESET_RESULT\",\"requestId\":%s,"
			"\"resetRunId\":\"%s\",\"state\":\"RESETTING\",\"result\":\"PENDING\"}\n",
			request, r->run_id);
		return;
	}
	snprintf(response, KR_RESPONSE_MAX,
		"{\"protocolVersion\":1,\"type\":\"RESET_RESULT\",\"requestId\":%s,"
		"\"deviceSerial\":\"%s\",\"deviceId\":%" PRId64 ",\"resetRunId\":\"%s\","
		"\"ownerGeneration\":\"%s\",\"fromOwnershipEpoch\":%" PRId64 ","
		"\"toOwnershipEpoch\":%" PRId64 ",\"resetNonce\":\"%s\","
		"\"deletionScope\":\"%s\",\"recordPolicy\":\"%s\",\"dataErasure\":\"LOGICAL\","
		"\"state\":\"RESET_DONE\",\"result\":\"CLEARED\","
		"\"proof\":{\"protocolVersion\":\"%s\",\"keyId\":\"%s\",\"signature\":\"%s\"}}\n",
		request, core->identity.serial, r->device_id, r->run_id, r->owner_generation,
		r->from_epoch, r->ownership_epoch, r->nonce, KR_RESET_SCOPE, kr_reset_policy_name(r->policy),
		KR_RESET_VERSION, core->identity.key_id, r->signature);
}

void kr_request(struct kr_core *core, char *frame, struct kr_status status,
	bool registration_open, char response[KR_RESPONSE_MAX])
{
	core->stop_peer = false;
	core->peer_after_stored.verified_time = 0;
	struct kr_json json;
	const char *error = "INVALID_MESSAGE";
	char request[224] = "null";
	char signature[87];
	if (!kr_json_parse(frame, &json)) { goto fail; }
	const char *id = kr_json_string(&json, "requestId");
	if (!id || !*id || strlen(id) > 36 || !kr_json_quote(id, request, sizeof(request))) { goto fail; }
	if (!equal(kr_json_number(&json, "protocolVersion"), "1")) {
		error = "UNSUPPORTED_PROTOCOL"; goto fail;
	}
	const char *type = kr_json_string(&json, "type");
	if (core->platform.application && core->platform.application(core->platform.ctx, &json, request, status, response)) { return; }
	if (equal(type, "GET_DEVICE_INFO")) {
		bool settings_ready = core->platform.issue_settings_nonce != NULL;
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"DEVICE_INFO\",\"requestId\":%s,"
			"\"deviceSerial\":\"%s\",\"firmwareVersion\":\"%s\",\"supportedProtocolVersions\":[1],"
			"\"supportedFunctions\":[\"GET_DEVICE_INFO\",\"GET_REGISTRATION_PROOF\",\"GET_STATUS\"%s%s],"
			"\"ownershipEpoch\":%" PRId64 ",\"resetContractVersion\":\"%s\",\"ownerState\":\"%s\","
			"\"supportedProofProtocolVersions\":[%s],"
			"\"maxFrameBytes\":%u,\"maxPayloadBytes\":%u,\"settingsPolicy\":\"%s\","
			"\"storedSettingsVersion\":%" PRId64 ",\"storedPayloadHash\":\"%s\","
			"\"supportedPayloadFormats\":[%s],\"storedPayloadFormat\":\"%s\","
			"\"profileStored\":%s,\"storedPayloadBytes\":%u,"
			"\"profileStorageCharacteristic\":\"6e400004-b5a3-f393-e0a9-e50e24dcca9e\","
			"\"profileRequiresEncryption\":true,"
			"\"peerExchangeProtocol\":\"tingle-peer-dev-v1\",\"maxPeerSessions\":16,"
			"\"storageHealthy\":%s,\"applied\":false,\"timeReady\":false}\n",
			request, core->identity.serial, KR_FIRMWARE_VERSION,
			settings_ready ? ",\"GET_SYNC_CHALLENGE\",\"SYNC_SETTINGS\"" : "",
			core->platform.accept_reset ? ",\"GET_RESET_CHALLENGE\",\"RESET_DEVICE\",\"GET_RESET_RESULT\"" : "",
			core->reset.ownership_epoch, core->platform.accept_reset ? KR_RESET_VERSION : "UNSUPPORTED",
			core->storage_error ? "UNKNOWN" : core->reset.phase == KR_RESETTING ? "RESETTING" :
				core->current.magic ? "BOUND" : "UNOWNED",
			core->reset.ownership_epoch ? "\"" KR_PROOF_VERSION_2 "\"" :
				"\"" KR_PROOF_VERSION "\",\"" KR_PROOF_VERSION_2 "\"",
			KR_FRAME_MAX, KR_PAYLOAD_MAX, settings_ready ? "DEV_SIGNED_V1" : "UNRESOLVED",
			core->current.version, core->current.hash,
			settings_ready ? "\"" KR_DEV_PAYLOAD_FORMAT "\",\"" KR_PROFILE_STORAGE_FORMAT "\"" : "",
			core->current.format, core->current.magic &&
				!strcmp(core->current.format, KR_PROFILE_STORAGE_FORMAT) ? "true" : "false",
			(unsigned)core->current.payload_size, core->storage_error ? "false" : "true");
		return;
	}
	if (equal(type, "GET_RESET_CHALLENGE")) {
		error = core->platform.issue_reset_challenge ?
			core->platform.issue_reset_challenge(core->platform.ctx, signature) : "RESET_UNSUPPORTED";
		if (error) { goto fail; }
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"RESET_CHALLENGE\",\"requestId\":%s,"
			"\"resetProtocolVersion\":\"%s\",\"keyId\":\"%s\",\"deviceSerial\":\"%s\","
			"\"ownershipEpoch\":%" PRId64 ",\"localOwnerGeneration\":\"%s\","
			"\"challengeId\":\"%s\",\"deviceNonce\":\"%s\",\"expiresInSeconds\":60,\"signature\":\"%s\"}\n",
			request, KR_RESET_VERSION, core->identity.key_id, core->identity.serial,
			core->reset.ownership_epoch, core->current.magic ? core->current.owner_generation : "NONE",
			core->reset_challenge.id, core->reset_challenge.nonce, signature);
		return;
	}
	if (equal(type, "RESET_DEVICE")) {
		error = core->platform.accept_reset ?
			core->platform.accept_reset(core->platform.ctx, &json) : "RESET_UNSUPPORTED";
		if (error) { goto fail; }
		reset_result(core, request, response);
		return;
	}
	if (equal(type, "GET_RESET_RESULT")) {
		if (!kr_reset_query_matches(core, kr_json_string(&json, "resetRunId"),
			kr_json_string(&json, "resetNonce"))) { error = "RESET_NOT_FOUND"; goto fail; }
		reset_result(core, request, response);
		return;
	}
	if (equal(type, "GET_SYNC_CHALLENGE")) {
		if (core->storage_error) { error = "STORAGE_ERROR"; goto fail; }
		if (core->reset.phase == KR_RESETTING) { error = "RESET_IN_PROGRESS"; goto fail; }
		char nonce[44];
		error = core->platform.issue_settings_nonce ?
			core->platform.issue_settings_nonce(core->platform.ctx, nonce) : "POLICY_UNRESOLVED";
		if (error) { goto fail; }
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"SYNC_CHALLENGE\",\"requestId\":%s,"
			"\"deviceNonce\":\"%s\",\"expiresInSeconds\":%d}\n",
			request, nonce, KR_SETTINGS_CHALLENGE_SECONDS);
		return;
	}
	if (equal(type, "GET_STATUS")) {
		char mv[20];
		if (status.battery_mv >= 0) { snprintf(mv, sizeof(mv), "%d", status.battery_mv); }
		else { strcpy(mv, "null"); }
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"STATUS\",\"requestId\":%s,"
			"\"batteryPercent\":null,\"batteryMillivolts\":%s,\"charging\":%s,"
			"\"storageTotalBytes\":null,\"storageUsedBytes\":null,"
			"\"storageState\":\"UNKNOWN\",\"pendingRecordCount\":null}\n",
			request, mv, status.charging < 0 ? "null" : status.charging ? "true" : "false");
		return;
	}
	if (equal(type, "GET_REGISTRATION_PROOF")) {
		if (core->storage_error) { error = "STORAGE_ERROR"; goto fail; }
		if (core->reset.phase == KR_RESETTING) { error = "RESET_IN_PROGRESS"; goto fail; }
		if (!registration_open) { error = "REGISTRATION_WINDOW_CLOSED"; goto fail; }
		bool version2 = equal(kr_json_string(&json, "proofProtocolVersion"), KR_PROOF_VERSION_2);
		if (core->reset.ownership_epoch && !version2) { error = "PROTOCOL_UPGRADE_REQUIRED"; goto fail; }
		if (version2 && core->current.magic) { error = "OWNER_MISMATCH"; goto fail; }
		int64_t expires;
		if ((!version2 && !equal(kr_json_string(&json, "proofProtocolVersion"), KR_PROOF_VERSION)) ||
			!kr_epoch(kr_json_string(&json, "expiresAt"), &expires)) { goto fail; }
		int result = version2 ? kr_registration_proof_v2(&core->identity,
			kr_json_string(&json, "challengeId"), kr_json_string(&json, "nonce"), expires,
			core->reset.ownership_epoch, signature) : kr_registration_proof(&core->identity,
			kr_json_string(&json, "challengeId"), kr_json_string(&json, "nonce"), expires, signature);
		if (result) { goto fail; }
		char binding[96] = "";
		if (version2) { snprintf(binding, sizeof(binding),
			"\"ownershipEpoch\":%" PRId64 ",\"ownerState\":\"UNOWNED\",", core->reset.ownership_epoch); }
		/* Expiry and single-use remain server checks while no trusted clock exists. */
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"REGISTRATION_PROOF\",\"requestId\":%s,"
			"\"deviceSerial\":\"%s\",%s\"proof\":{\"protocolVersion\":\"%s\",\"keyId\":\"%s\","
			"\"signature\":\"%s\"}}\n", request, core->identity.serial, binding,
			version2 ? KR_PROOF_VERSION_2 : KR_PROOF_VERSION,
			core->identity.key_id, signature);
		return;
	}
	if (equal(type, "SYNC_SETTINGS")) {
		error = settings(core, &json, signature);
		if (error) { goto fail; }
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"SETTINGS_RESULT\",\"requestId\":%s,"
			"\"syncRunId\":\"%s\",\"settingsVersion\":%" PRId64 ",\"payloadHash\":\"%s\","
			"\"stored\":true,\"applied\":false,\"timeReady\":false,"
			"\"proof\":{\"protocolVersion\":\"%s\",\"keyId\":\"%s\",\"signature\":\"%s\"}}\n",
			request, candidate.sync_run, candidate.version, candidate.hash,
			equal(kr_json_string(&json, "proofProtocolVersion"), KR_PROOF_VERSION_2) ? KR_PROOF_VERSION_2 : KR_PROOF_VERSION,
			core->identity.key_id, signature);
		return;
	}
	error = "UNSUPPORTED_TYPE";
fail:
	snprintf(response, KR_RESPONSE_MAX,
		"{\"protocolVersion\":1,\"type\":\"ERROR\",\"requestId\":%s,\"code\":\"%s\"}\n", request, error);
}

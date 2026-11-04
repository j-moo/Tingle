#include "registration.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* Called by one application worker, never from a Bluetooth callback. */
static struct kr_settings candidate;

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
	if (!equal(kr_json_string(json, "payloadEncoding"), "base64url") ||
		!equal(kr_json_string(json, "proofProtocolVersion"), KR_PROOF_VERSION) ||
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
	/* serverTime is deliberately not a trusted clock until authenticated time is
	 * specified. Storage proof does not imply that matching is enabled. */
	return kr_store(core, &candidate, signature);
}

void kr_request(struct kr_core *core, char *frame, struct kr_status status,
	bool registration_open, char response[KR_RESPONSE_MAX])
{
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
	if (equal(type, "GET_DEVICE_INFO")) {
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"DEVICE_INFO\",\"requestId\":%s,"
			"\"deviceSerial\":\"%s\",\"firmwareVersion\":\"%s\",\"supportedProtocolVersions\":[1],"
			"\"supportedFunctions\":[\"GET_DEVICE_INFO\",\"GET_REGISTRATION_PROOF\",\"GET_STATUS\"],"
			"\"maxFrameBytes\":%u,\"maxPayloadBytes\":%u,\"settingsPolicy\":\"UNRESOLVED\","
			"\"storedSettingsVersion\":%" PRId64 ",\"storedPayloadHash\":\"%s\","
			"\"storageHealthy\":%s,\"applied\":false,\"timeReady\":false}\n",
			request, core->identity.serial, KR_FIRMWARE_VERSION, KR_FRAME_MAX, KR_PAYLOAD_MAX,
			core->current.version, core->current.hash, core->storage_error ? "false" : "true");
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
		if (!registration_open) { error = "REGISTRATION_WINDOW_CLOSED"; goto fail; }
		int64_t expires;
		if (!equal(kr_json_string(&json, "proofProtocolVersion"), KR_PROOF_VERSION) ||
			!kr_epoch(kr_json_string(&json, "expiresAt"), &expires) ||
			kr_registration_proof(&core->identity, kr_json_string(&json, "challengeId"),
				kr_json_string(&json, "nonce"), expires, signature)) { goto fail; }
		/* Expiry and single-use remain server checks while no trusted clock exists. */
		snprintf(response, KR_RESPONSE_MAX,
			"{\"protocolVersion\":1,\"type\":\"REGISTRATION_PROOF\",\"requestId\":%s,"
			"\"deviceSerial\":\"%s\",\"proof\":{\"protocolVersion\":\"%s\",\"keyId\":\"%s\","
			"\"signature\":\"%s\"}}\n", request, core->identity.serial, KR_PROOF_VERSION,
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
			request, candidate.sync_run, candidate.version, candidate.hash, KR_PROOF_VERSION,
			core->identity.key_id, signature);
		return;
	}
	error = "UNSUPPORTED_TYPE";
fail:
	snprintf(response, KR_RESPONSE_MAX,
		"{\"protocolVersion\":1,\"type\":\"ERROR\",\"requestId\":%s,\"code\":\"%s\"}\n", request, error);
}

#ifndef TINGLE_REGISTRATION_H
#define TINGLE_REGISTRATION_H

#include "peer_plan.h"
#include "reset.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define KR_PROOF_VERSION "tingle-device-proof-v1"
#define KR_SETTINGS_PROOF_VERSION "tingle-dev-settings-proof-v1"
#define KR_PROOF_VERSION_2 "tingle-device-proof-v2"
#define KR_SETTINGS_PROOF_VERSION_2 "tingle-dev-settings-proof-v2"
#define KR_DEV_PAYLOAD_FORMAT "tingle-dev-settings-v1"
#define KR_SETTINGS_CHALLENGE_SECONDS 60
#ifndef KR_FIRMWARE_VERSION
#define KR_FIRMWARE_VERSION "0.1.0-registration"
#endif
/* Implementation limits, to be agreed with FE/BE before production rollout. */
#define KR_PAYLOAD_MAX 2048
#define KR_FRAME_MAX 4096
#define KR_RESPONSE_MAX 2304
#define KR_RECORD_MAGIC UINT32_C(0x4b525301)

struct kr_identity {
	char serial[65];
	char key_id[65];
	uint8_t secret[64];
	uint8_t public_key[32];
};

/* One NVS item: metadata and original payload commit together. Never store a
 * partial request. Native layout is versioned by magic; no cross-ABI migration. */
struct kr_settings {
	uint32_t magic;
	uint32_t payload_size;
	int64_t device_id;
	int64_t version;
	char owner_generation[37];
	char sync_run[37];
	char nonce[44];
	char hash[65];
	char format[65];
	uint8_t payload[KR_PAYLOAD_MAX];
};

struct kr_settings_auth {
	const char *protocol_version;
	const char *server_key_id;
	const char *device_nonce;
	const char *server_time;
	const char *signature;
	int64_t ownership_epoch;
	bool version2;
};

struct kr_settings_nonce {
	char value[44];
	int64_t deadline_ms;
};

struct kr_status;
struct kr_platform {
	void *ctx;
	/* Must complete a durable atomic write AND full readback before returning 0. */
	int (*save)(void *ctx, const struct kr_settings *next);
	/* Verifies authenticated server/owner binding and supported payload schema.
	 * Default board adapter returns POLICY_UNRESOLVED until the contract exists.
	 * SHA256 alone is NOT sender authentication. Called even for retry ACKs. */
	const char *(*authorize)(void *ctx, const struct kr_settings *next,
		const struct kr_settings_auth *auth, struct kr_peer_plan *plan);
	const char *(*issue_settings_nonce)(void *ctx, char out[44]);
	const char *(*issue_reset_challenge)(void *ctx, char signature[87]);
	const char *(*begin_reset)(void *ctx, const struct kr_reset_record *next);
	int (*save_reset)(void *ctx, const struct kr_reset_record *next);
	const char *(*erase_owner)(void *ctx);
	const char *(*accept_reset)(void *ctx, const struct kr_json *json);
	bool (*application)(void *ctx, const struct kr_json *json, const char *request,
		struct kr_status status, char *response);
};

struct kr_core {
	struct kr_identity identity;
	struct kr_settings current;
	struct kr_platform platform;
	bool storage_error;
	bool profile_transport_protected; /* Per-request RAM state; never part of NVS. */
	uint32_t connection_epoch;
	bool stop_peer;
	struct kr_peer_plan peer_after_stored;
	struct kr_reset_record reset;
	struct kr_reset_challenge reset_challenge;
};

void kr_hash(const void *data, size_t len, uint8_t out[32]);
void kr_hex(const uint8_t *data, size_t len, char *out);
size_t kr_base64_encode(const uint8_t *src, size_t len, char *dst);
int kr_base64_decode(const char *src, uint8_t *dst, size_t capacity);
bool kr_identifier(const char *text);
bool kr_uuid(const char *text);
bool kr_nonce(const char *text);
bool kr_positive(const char *text, int64_t *out);
bool kr_epoch(const char *iso, int64_t *out);
bool kr_settings_valid(const struct kr_settings *settings);
int kr_registration_proof(const struct kr_identity *id, const char *challenge,
	const char *nonce, int64_t expires, char signature[87]);
int kr_registration_proof_v2(const struct kr_identity *id, const char *challenge,
	const char *nonce, int64_t expires, int64_t ownership_epoch, char signature[87]);
const char *kr_store(struct kr_core *core, const struct kr_settings *next,
	const struct kr_settings_auth *auth, char signature[87]);
void kr_issue_settings_nonce(struct kr_settings_nonce *challenge, const uint8_t bytes[32], int64_t now_ms);
const char *kr_verify_dev_settings(const struct kr_identity *id, const struct kr_settings *next,
	const struct kr_settings_auth *auth, struct kr_settings_nonce *challenge, int64_t now_ms,
	const char *server_key_id, const uint8_t server_key[32], int64_t *verified_time);
bool kr_peer_after_response(struct kr_core *core, bool sent, struct kr_peer_plan *out);

/* Flat JSON requests, strict duplicate/type/overflow checks. Values are decoded
 * in-place only after complete LF framing. No heap or floating-point integers. */
struct kr_field { char *key; char *value; bool string; };
struct kr_json { struct kr_field fields[24]; size_t count; };
bool kr_json_parse(char *text, struct kr_json *out);
const char *kr_json_string(const struct kr_json *obj, const char *key);
const char *kr_json_number(const struct kr_json *obj, const char *key);
size_t kr_json_quote(const char *text, char *out, size_t capacity);

struct kr_status {
	int battery_mv, charging;
	int gauge_percent;
	bool gauge_ready, gauge_percent_valid, presence_valid, battery_present;
	bool usb_valid, usb_present;
};
struct kr_frame { char bytes[KR_FRAME_MAX + 1]; size_t size; };
/* 0: incomplete, 1: complete, -1: malformed (caller disconnects).
 * A disconnect always resets this object, never the durable settings. */
int kr_frame_push(struct kr_frame *frame, const void *bytes, size_t length);
void kr_frame_reset(struct kr_frame *frame);
void kr_request(struct kr_core *core, char *frame, struct kr_status status,
	bool registration_open, char response[KR_RESPONSE_MAX]);
void kr_request_forget_candidate(void);

#endif

#ifndef TINGLE_REGISTRATION_H
#define TINGLE_REGISTRATION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define KR_PROOF_VERSION "tingle-device-proof-v1"
#define KR_FIRMWARE_VERSION "0.1.0-registration"
/* Implementation limits, to be agreed with FE/BE before production rollout. */
#define KR_PAYLOAD_MAX 2048
#define KR_FRAME_MAX 4096
#define KR_RESPONSE_MAX 1536
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

struct kr_platform {
	void *ctx;
	/* Must complete a durable atomic write AND full readback before returning 0. */
	int (*save)(void *ctx, const struct kr_settings *next);
	/* Verifies authenticated server/owner binding and supported payload schema.
	 * Default board adapter returns POLICY_UNRESOLVED until the contract exists.
	 * SHA256 alone is NOT sender authentication. Called even for retry ACKs. */
	const char *(*authorize)(void *ctx, const struct kr_settings *next);
};

struct kr_core {
	struct kr_identity identity;
	struct kr_settings current;
	struct kr_platform platform;
	bool storage_error;
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
const char *kr_store(struct kr_core *core, const struct kr_settings *next,
	char signature[87]);

/* Flat JSON requests, strict duplicate/type/overflow checks. Values are decoded
 * in-place only after complete LF framing. No heap or floating-point integers. */
struct kr_field { char *key; char *value; bool string; };
struct kr_json { struct kr_field fields[24]; size_t count; };
bool kr_json_parse(char *text, struct kr_json *out);
const char *kr_json_string(const struct kr_json *obj, const char *key);
const char *kr_json_number(const struct kr_json *obj, const char *key);
size_t kr_json_quote(const char *text, char *out, size_t capacity);

struct kr_status { int battery_mv; int charging; };
struct kr_frame { char bytes[KR_FRAME_MAX + 1]; size_t size; };
/* 0: incomplete, 1: complete, -1: malformed (caller disconnects).
 * A disconnect always resets this object, never the durable settings. */
int kr_frame_push(struct kr_frame *frame, const void *bytes, size_t length);
void kr_frame_reset(struct kr_frame *frame);
void kr_request(struct kr_core *core, char *frame, struct kr_status status,
	bool registration_open, char response[KR_RESPONSE_MAX]);

#endif

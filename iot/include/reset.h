#ifndef TINGLE_RESET_H
#define TINGLE_RESET_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define KR_RESET_VERSION "tingle-keyring-reset-v1"
#define KR_RESET_SCOPE "OWNER_DATA_V1"
#define KR_RESET_MAGIC UINT32_C(0x4b525201)
#define KR_RESET_SECONDS 60
enum kr_reset_phase { KR_RESET_NONE, KR_RESETTING, KR_RESET_DONE };
enum kr_reset_policy { KR_RESET_REQUIRE_SYNCED, KR_RESET_DISCARD };
/* A separate versioned record preserves the installed settings layout. */
struct kr_reset_record {
	uint32_t magic;
	uint32_t phase;
	int64_t ownership_epoch;
	int64_t from_epoch;
	int64_t device_id;
	uint32_t policy;
	char run_id[37];
	char owner_generation[37];
	char nonce[44];
	char signature[87];
};
struct kr_reset_challenge {
	char id[37];
	char nonce[44];
	int64_t deadline_ms;
};
struct kr_core;
struct kr_json;
bool kr_nonnegative(const char *text, int64_t *out);
bool kr_reset_record_valid(const struct kr_core *core);
const char *kr_reset_issue(struct kr_core *core, const uint8_t bytes[48], int64_t now_ms,
	char signature[87]);
const char *kr_reset_begin(struct kr_core *core, const struct kr_json *json,
	int64_t now_ms, const char *server_key_id, const uint8_t server_key[32]);
const char *kr_reset_resume(struct kr_core *core);
bool kr_reset_query_matches(const struct kr_core *core, const char *run, const char *nonce);
const char *kr_reset_policy_name(uint32_t policy);
#endif

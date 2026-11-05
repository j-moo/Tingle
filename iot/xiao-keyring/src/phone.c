#include "phone.h"
#include <string.h>

size_t kr_phone_frame(uint8_t op, uint32_t request, const uint8_t *body,
	size_t len, uint8_t *out)
{
	if (len > KR_FRAME_MAX - 14) {
		return 0;
	}
	out[0] = 'K';
	out[1] = 'P';
	out[2] = KR_VERSION;
	out[3] = op;
	kr_put32(out + 4, request);
	kr_put16(out + 8, (uint16_t)len);
	memmove(out + 10, body, len);
	kr_put32(out + 10 + len, kr_crc32(out, 10 + len));
	return len + 14;
}

static enum kr_result execute(struct kr_engine *e, uint8_t op, const uint8_t *p,
	size_t len, uint8_t *out, size_t *out_len, uint64_t *new_utc)
{
	*out_len = 0;
	if (op == KR_STATUS && len == 0) {
		kr_put64(out, e->config.device);
		out[8] = e->config.registered;
		out[9] = e->ready;
		kr_put32(out + 10, e->config.profile.revision);
		kr_put16(out + 14, (uint16_t)kr_count(e, KR_MATCH));
		kr_put16(out + 16, (uint16_t)kr_count(e, KR_CONFIRMED));
		kr_put16(out + 18, (uint16_t)kr_count(e, KR_REVIEW));
		kr_put16(out + 20, (uint16_t)kr_count(e, KR_EMPTY));
		kr_put32(out + 22, e->overflow);
		out[26] = e->storage_error;
		out[27] = 255; /* Battery is unmeasured, never a fabricated zero. */
		*out_len = 28;
		return KR_OK;
	}
	if (op == KR_REGISTER && len == 32) {
		return kr_register(e, p, p + 16);
	}
	if (len < 16 || !kr_auth(e, p)) {
		return KR_UNAUTHORIZED;
	}
	p += 16;
	len -= 16;
	if (op == KR_SYNC && len >= 42) {
		uint64_t utc = kr_get64(p);
		struct kr_profile profile;
		if (!utc || utc > INT64_MAX / 1000 || !kr_profile_decode(&profile, p + 8, len - 8)) {
			return KR_INVALID;
		}
		enum kr_result result = kr_sync(e, &profile);
		if (result == KR_OK) {
			*new_utc = utc;
			kr_put32(out, profile.revision);
			memcpy(out + 4, profile.snapshot, 16);
			*out_len = 20;
		}
		return result;
	}
	if (op == KR_READ_RECORD && len == 5 && (p[0] == KR_CONFIRMED || p[0] == KR_REVIEW)) {
		uint32_t after = kr_get32(p + 1);
		const struct kr_record *found = NULL;
		for (unsigned i = 0; i < KR_RECORDS; ++i) {
			const struct kr_record *r = &e->records[i];
			if (r->kind == p[0] && r->sequence > after &&
				(!found || r->sequence < found->sequence)) {
				found = r;
			}
		}
		if (!found) {
			return KR_NOT_FOUND;
		}
		*out_len = kr_record_encode(found, out);
		return KR_OK;
	}
	/* Lab-only receipt, not a production server signature. No ACK on READ. */
	if (op == KR_LAB_STORED_RECEIPT && len == 29 && kr_get64(p) == e->config.device && p[28] == 1) {
		for (unsigned i = 0; i < KR_RECORDS; ++i) {
			const struct kr_record *r = &e->records[i];
			if (r->kind && memcmp(r->id, p + 8, 16) == 0) {
				size_t n = kr_record_encode(r, out);
				if (kr_crc32(out, n) != kr_get32(p + 24)) {
					return KR_CONFLICT;
				}
				return kr_ack(e, p + 8);
			}
		}
		return kr_ack(e, p + 8); /* Idempotent receipt retry after deletion. */
	}
	if (op == KR_LAB_RELEASE && len == 16) {
		return kr_release(e, p);
	}
	return KR_INVALID;
}

size_t kr_phone_process(struct kr_engine *e, const uint8_t *in, size_t len,
	uint8_t *out, uint64_t *new_utc)
{
	*new_utc = 0;
	if (len < 14 || len > KR_FRAME_MAX || in[0] != 'K' || in[1] != 'P' ||
		in[2] != KR_VERSION || in[3] & 0x80 || (size_t)kr_get16(in + 8) + 14 != len ||
		kr_get32(in + len - 4) != kr_crc32(in, len - 4)) {
		return 0;
	}
	size_t payload_len = 0;
	enum kr_result result = execute(e, in[3], in + 10, len - 14,
		out + 11, &payload_len, new_utc);
	out[10] = (uint8_t)result;
	if (result != KR_OK) {
		payload_len = 0;
	}
	return kr_phone_frame(in[3] | 0x80, kr_get32(in + 4), out + 10, payload_len + 1, out);
}

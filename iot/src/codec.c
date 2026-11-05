#include "keyring.h"
#include <string.h>

uint16_t kr_get16(const uint8_t *p)
{
	return (uint16_t)p[0] | (uint16_t)p[1] << 8;
}

uint32_t kr_get32(const uint8_t *p)
{
	return (uint32_t)kr_get16(p) | (uint32_t)kr_get16(p + 2) << 16;
}

uint64_t kr_get64(const uint8_t *p)
{
	return (uint64_t)kr_get32(p) | (uint64_t)kr_get32(p + 4) << 32;
}

void kr_put16(uint8_t *p, uint16_t n)
{
	p[0] = (uint8_t)n;
	p[1] = (uint8_t)(n >> 8);
}

void kr_put32(uint8_t *p, uint32_t n)
{
	kr_put16(p, (uint16_t)n);
	kr_put16(p + 2, (uint16_t)(n >> 16));
}

void kr_put64(uint8_t *p, uint64_t n)
{
	kr_put32(p, (uint32_t)n);
	kr_put32(p + 4, (uint32_t)(n >> 32));
}

uint32_t kr_crc32(const uint8_t *data, size_t len)
{
	uint32_t crc = UINT32_MAX;
	for (size_t i = 0; i < len; ++i) {
		crc ^= data[i];
		for (unsigned b = 0; b < 8; ++b) {
			crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
		}
	}
	return ~crc;
}

size_t kr_profile_encode(const struct kr_profile *p, uint8_t *out)
{
	if (p->data_len > KR_DATA_MAX) {
		return 0;
	}
	kr_put32(out, p->revision);
	kr_put32(out + 4, p->policy);
	kr_put32(out + 8, p->rule);
	kr_put32(out + 12, p->opportunity_sec);
	memcpy(out + 16, p->snapshot, 16);
	kr_put16(out + 32, p->data_len);
	memcpy(out + 34, p->data, p->data_len);
	return 34 + p->data_len;
}

bool kr_profile_decode(struct kr_profile *p, const uint8_t *in, size_t len)
{
	if (len < 34 || kr_get16(in + 32) > KR_DATA_MAX ||
		len != (size_t)34 + kr_get16(in + 32)) {
		return false;
	}
	memset(p, 0, sizeof(*p));
	p->revision = kr_get32(in);
	p->policy = kr_get32(in + 4);
	p->rule = kr_get32(in + 8);
	p->opportunity_sec = kr_get32(in + 12);
	memcpy(p->snapshot, in + 16, 16);
	p->data_len = kr_get16(in + 32);
	memcpy(p->data, in + 34, p->data_len);
	return p->revision && p->policy && p->rule && p->opportunity_sec && p->data_len;
}

/* Advertising packet: "TK", version, type, body length LE16, body, CRC32. */
size_t kr_packet(uint8_t type, const uint8_t *body, size_t len, uint8_t *out)
{
	if (len > KR_WIRE_MAX - 10) {
		return 0;
	}
	out[0] = 'T';
	out[1] = 'K';
	out[2] = KR_VERSION;
	out[3] = type;
	kr_put16(out + 4, (uint16_t)len);
	memcpy(out + 6, body, len);
	kr_put32(out + 6 + len, kr_crc32(out, 6 + len));
	return len + 10;
}

bool kr_packet_valid(const uint8_t *in, size_t len)
{
	return len >= 10 && len <= KR_WIRE_MAX && in[0] == 'T' && in[1] == 'K' &&
		in[2] == KR_VERSION && (size_t)kr_get16(in + 4) + 10 == len &&
		kr_get32(in + len - 4) == kr_crc32(in, len - 4);
}

/* Canonical phone export: fixed metadata + frozen own profile + original peer packet. */
size_t kr_record_encode(const struct kr_record *r, uint8_t *out)
{
	out[0] = KR_VERSION;
	out[1] = r->kind;
	/* Export is immutable even if a final radio receipt arrives after phone upload. */
	out[2] = r->kind == KR_CONFIRMED || r->kind == KR_REVIEW ? KR_COMPLETE : r->phase;
	out[3] = r->reason;
	kr_put32(out + 4, r->sequence);
	memcpy(out + 8, r->id, 16);
	kr_put64(out + 24, r->peer);
	kr_put64(out + 32, r->occurred_at);
	kr_put64(out + 40, r->expires_at);
	size_t own_len = kr_profile_encode(&r->own, out + 52);
	kr_put16(out + 48, (uint16_t)own_len);
	kr_put16(out + 50, r->peer_len);
	memcpy(out + 52 + own_len, r->peer_wire, r->peer_len);
	return 52 + own_len + r->peer_len;
}

#include "registration.h"
#include "peer.h"
#include "policy.h"
#include "matching.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* Compact representation of the EXISTING signed settings transcript. It is
 * transmitted only inside AUTH encryption and contains no profile plaintext. */
static uint64_t get64(const uint8_t *p)
{
	uint64_t n = 0; for (unsigned i = 0; i < 8; ++i) { n |= (uint64_t)p[i] << (i * 8); } return n;
}
static void put64(uint8_t *p, uint64_t n)
{
	for (unsigned i = 0; i < 8; ++i) { p[i] = (uint8_t)(n >> (i * 8)); }
}
static int hex(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; }
static bool unhex(const char *s, uint8_t *p, unsigned n, bool uuid)
{
	for (unsigned i = 0; i < n; ++i) {
		if (uuid && (i == 4 || i == 6 || i == 8 || i == 10)) { if (*s++ != '-') { return false; } }
		int a = hex(*s++), b = hex(*s++); if (a < 0 || b < 0) { return false; } p[i] = (uint8_t)(a * 16 + b);
	}
	return !*s;
}
static void uuid(const uint8_t *p, char out[37])
{
	unsigned j = 0;
	for (unsigned i = 0; i < 16; ++i) {
		if (i == 4 || i == 6 || i == 8 || i == 10) { out[j++] = '-'; }
		kr_hex(p + i, 1, out + j); j += 2;
	}
}
static int transcript(const uint8_t *c, const uint8_t server[32], char text[768])
{
	char serial[22] = "XIAO-", key[25] = "ed25519-", server_id[29] = "dev-ed25519-";
	char owner[37], run[37], hash[65], nonce[44], device_nonce[44];
	kr_hex(c + 1, 8, serial + 5); kr_hex(c + 165, 8, key + 8); kr_hex(server, 8, server_id + 12);
	uuid(c + 29, owner); uuid(c + 45, run); kr_hex(c + 133, 32, hash);
	kr_base64_encode(c + 61, 32, nonce); kr_base64_encode(c + 93, 32, device_nonce);
	uint64_t revision = 0; for (unsigned i = 0; i < 4; ++i) { revision |= (uint64_t)c[17 + i] << (8 * i); }
	int n = snprintf(text, 768,
		"TINGLE-KEYRING-SERVER-SETTINGS\n%s\n%s\n%s\n%s\n%" PRIu64 "\n%s\n%s\n"
		"%" PRIu64 "\n%s\n%s\n%s\n%s\n%" PRIu64 "\n",
		c[0] == 2 ? KR_SETTINGS_PROOF_VERSION_2 : KR_SETTINGS_PROOF_VERSION,
		server_id, serial, key, get64(c + 9), owner, run, revision, KR_PROFILE_STORAGE_FORMAT,
		hash, nonce, device_nonce, get64(c + 125));
	if (c[0] == 2 && n > 0 && n < 768) { n += snprintf(text + n, 768 - (size_t)n, "%" PRIu64 "\n", get64(c + 21)); }
	return n > 0 && n < 768 ? n : -1;
}
static size_t binding(uint8_t out[320], const uint8_t *cert, const uint8_t *ephemeral)
{
	static const char domain[] = "TINGLE-PEER-AUTH-V1\n";
	memcpy(out, domain, sizeof(domain) - 1); memcpy(out + sizeof(domain) - 1, ephemeral, 32);
	memcpy(out + sizeof(domain) - 1 + 32, cert, 261);
	return sizeof(domain) - 1 + 32 + 261;
}
bool kr_peer_grant(const uint8_t c[261], const uint8_t server[32], uint64_t now)
{
	const char *domain = c[0] == 4 ? "TINGLE-PEER-GRANT-V3\n" : "TINGLE-PEER-GRANT-V2\n";
	size_t domain_len = strlen(domain);
	uint8_t message[224]; memcpy(message, domain, domain_len); memcpy(message + domain_len, c, 197);
	uint32_t revision = 0, policy = 0, rule = 0;
	for (unsigned i = 0; i < 4; ++i) {
		revision |= (uint32_t)c[17 + i] << (8 * i); policy |= (uint32_t)c[109 + i] << (8 * i); rule |= (uint32_t)c[113 + i] << (8 * i);
	}
	uint8_t empty[16] = {0};
	if (c[0] == 4) {
		uint32_t opportunity = (uint32_t)(get64(c + 21) >> 32);
		if (!opportunity || opportunity > 365 * 86400) { return false; }
	}
	return (c[0] == 3 || c[0] == 4) && revision && get64(c + 9) && get64(c + 9) <= INT64_MAX &&
		(c[0] == 4 || get64(c + 21) <= INT64_MAX) && memcmp(c + 93, empty, 16) && policy == KR_MATCH_POLICY && rule == KR_MATCH_RULE &&
		get64(c + 117) <= INT64_MAX && get64(c + 117) > get64(c + 125) && now < get64(c + 117) &&
		get64(c + 125) && now && now <= INT64_MAX - 300 && get64(c + 125) <= now + 300 &&
		!crypto_ed25519_check(c + 197, server, message, domain_len + 197);
}
bool kr_peer_grant_matches(const struct kr_identity *id, const struct kr_settings *s,
	const struct kr_settings_auth *a, const uint8_t g[261])
{
	struct kr_policy policy; uint8_t expected[92] = {0}, hash[32];
	if (!a || s->version <= 0 || s->version > UINT32_MAX || strlen(id->serial) != 21 ||
		memcmp(id->serial, "XIAO-", 5) || !kr_policy_decode(s->payload, s->payload_size, &policy)) { return false; }
	if (!unhex(id->serial + 5, expected, 8, false) || !unhex(s->owner_generation, expected + 28, 16, true) ||
		!unhex(s->sync_run, expected + 44, 16, true) || kr_base64_decode(s->nonce, expected + 60, 32) != 32) { return false; }
	put64(expected + 8, (uint64_t)s->device_id); put64(expected + 20, a->version2 ? (uint64_t)a->ownership_epoch : 0);
	for (unsigned i = 0; i < 4; ++i) { expected[16 + i] = (uint8_t)((uint64_t)s->version >> (8 * i)); }
	kr_hash(policy.profile, 196, hash);
	uint64_t epoch = a->version2 ? (uint64_t)a->ownership_epoch : 0;
	uint64_t grant_epoch = g[0] == 4 ? (uint32_t)get64(g + 21) : get64(g + 21);
	return !memcmp(g + 1, expected, 20) && grant_epoch == epoch && !memcmp(g + 29, expected + 28, 64) &&
		!memcmp(g + 133, hash, 32) && !memcmp(g + 165, id->public_key, 32);
}
bool kr_peer_material_make(struct kr_peer_material *out, const struct kr_identity *id,
	const struct kr_settings *s, const struct kr_settings_auth *a, const uint8_t seed[32])
{
	memset(out, 0, sizeof(*out)); struct kr_profile_data data; struct kr_policy policy;
	bool operational = (!strcmp(s->format, KR_POLICY_FORMAT) || !strcmp(s->format, KR_POLICY_LEGACY_FORMAT)) &&
		kr_policy_decode(s->payload, s->payload_size, &policy);
	const uint8_t *payload = operational ? policy.profile : s->payload;
	if (!a || !seed || s->version <= 0 || s->version > UINT32_MAX ||
		!kr_profile_data_decode(payload, operational ? 196 : s->payload_size, &data) || !(data.options & 1) ||
		strlen(id->serial) != 21 || memcmp(id->serial, "XIAO-", 5)) { return false; }
	char expected[25] = "ed25519-"; kr_hex(id->public_key, 8, expected + 8);
	if (strcmp(id->key_id, expected)) { return false; }
	uint8_t *c = out->certificate;
	c[0] = a->version2 ? 2 : 1;
	if (!unhex(id->serial + 5, c + 1, 8, false) || !unhex(s->owner_generation, c + 29, 16, true) ||
		!unhex(s->sync_run, c + 45, 16, true) || !unhex(s->hash, c + 133, 32, false) ||
		kr_base64_decode(s->nonce, c + 61, 32) != 32 || kr_base64_decode(a->device_nonce, c + 93, 32) != 32 ||
		kr_base64_decode(a->signature, c + 197, 64) != 64) { return false; }
	int64_t epoch; if (!kr_epoch(a->server_time, &epoch)) { return false; }
	put64(c + 9, (uint64_t)s->device_id); put64(c + 21, a->version2 ? (uint64_t)a->ownership_epoch : 0);
	put64(c + 125, (uint64_t)epoch);
	for (unsigned i = 0; i < 4; ++i) { c[17 + i] = (uint8_t)((uint64_t)s->version >> (8 * i)); }
	memcpy(c + 165, id->public_key, 32);
	if (operational) {
		uint8_t digest[32]; kr_hash(payload, 196, digest);
		/* The private settings hash differs from the public profile hash. Bind
		 * grant identity/run/revision/nonce to the enclosing signed settings. */
		if (!kr_peer_grant_matches(id, s, a, policy.grant) ||
			memcmp(policy.grant + 133, digest, 32) || get64(policy.grant + 125) > (uint64_t)epoch) { return false; }
		memcpy(c, policy.grant, 261); memcpy(out->owner_ref, c + 93, 16);
		out->policy_ready = policy.complete && policy.enabled;
	} else { memcpy(out->owner_ref, c + 29, 16); }
	out->opportunity_seconds = operational ? policy.opportunity_seconds : 7 * 86400;
	memcpy(out->secret, seed, 32);
	crypto_x25519_public_key(out->public_key, out->secret);
	uint8_t message[320]; size_t n = binding(message, c, out->public_key);
	crypto_ed25519_sign(c + 261, id->secret, message, n);
	memcpy(out->payload, payload, 196); memcpy(out->owner, c + 29, 16);
	out->device = get64(c + 1) & INT64_MAX; out->revision = (uint32_t)s->version;
	out->valid = out->device != 0; return out->valid;
}
void kr_peer_scope(const uint8_t c[KR_PEER_CERT_BYTES], uint64_t *epoch, uint8_t scope[16])
{
	*epoch = c[0] == 4 ? (uint32_t)get64(c + 21) : get64(c + 21);
	uint8_t binding[72] = "TINGLE-OWNER-V1";
	memcpy(binding + 16, c + 1, 8); memcpy(binding + 24, c + 29, 16);
	memcpy(binding + 40, c + (c[0] >= 3 ? 93 : 29), 16); put64(binding + 56, *epoch);
	crypto_blake2b(scope, 16, binding, sizeof(binding));
}
bool kr_peer_certificate(const uint8_t c[KR_PEER_CERT_BYTES], const uint8_t ephemeral[32],
	const uint8_t server[32], uint64_t now, struct kr_peer_verified *out)
{
	if ((c[0] < 1 || c[0] > 4) || !get64(c + 9) || get64(c + 9) > INT64_MAX ||
		(c[0] != 4 && get64(c + 21) > INT64_MAX) || (c[0] == 1 && get64(c + 21)) ||
		!get64(c + 125) || get64(c + 125) > INT64_MAX || !now || get64(c + 125) > now + 300) { return false; }
	if (c[0] >= 3) { if (!kr_peer_grant(c, server, now)) { return false; } }
	else {
		char text[768]; int n = transcript(c, server, text);
		if (n < 0 || crypto_ed25519_check(c + 197, server, (const uint8_t *)text, (size_t)n)) { return false; }
	}
	uint8_t message[320]; size_t size = binding(message, c, ephemeral);
	if (crypto_ed25519_check(c + 261, c + 165, message, size)) { return false; }
	memset(out, 0, sizeof(*out)); out->device = get64(c + 1) & INT64_MAX;
	for (unsigned i = 0; i < 4; ++i) { out->revision |= (uint32_t)c[17 + i] << (8 * i); }
	memcpy(out->hash, c + 133, 32); memcpy(out->owner, c + 29, 16);
	memcpy(out->owner_ref, c + (c[0] >= 3 ? 93 : 29), 16);
	out->valid_until = c[0] >= 3 ? get64(c + 117) : INT64_MAX;
	out->opportunity_seconds = c[0] == 4 ? (uint32_t)(get64(c + 21) >> 32) : 7 * 86400;
	out->policy_ready = c[0] >= 3;
	kr_peer_scope(c, &out->ownership_epoch, out->scope);
	return out->device && out->revision;
}

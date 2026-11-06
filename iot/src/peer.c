#include "peer.h"
#include "keyring.h"
#include "matching.h"
#include "monocypher.h"
#include <string.h>

void kr_hash(const void *data, size_t length, uint8_t out[32]);

static bool zero(const uint8_t *p, size_t n)
{
	unsigned v = 0; for (size_t i = 0; i < n; ++i) { v |= p[i]; } return v == 0;
}
static enum kr_decision calculate(void *ctx, const struct kr_profile *a, const struct kr_profile *b)
{
	(void)ctx; struct kr_pair_score score;
	if (!kr_match_score(a->data, a->data_len, b->data, b->data_len, &score)) { return KR_CALC_ERROR; }
	int eligible = kr_match_eligibility(a->data, a->data_len, b->data, b->data_len);
	if (eligible < 0 || score.ab.unresolved || score.ba.unresolved ||
		!score.ab.has_score || !score.ba.has_score || score.ab.coverage < .7 || score.ba.coverage < .7) {
		return KR_CALC_ERROR;
	}
	return eligible && score.match ? KR_ACCEPT : KR_REJECT;
}
void kr_peer_stop(struct kr_peer_runtime *p)
{
	/* Public nonce counter survives a stop/retry in the SAME boot. Even if an
	 * adapter retries the same material, it must never reuse an AEAD nonce. A
	 * power cycle cannot resume without a new signed sync and fresh CSPRNG key. */
	uint64_t sequence = p->sequence; crypto_wipe(p, sizeof(*p)); p->sequence = sequence;
}

int kr_peer_start(struct kr_peer_runtime *p, const struct kr_peer_material *own,
	const uint8_t server_key[32], struct kr_engine *e)
{
	kr_peer_stop(p); e->ready = false;
	struct kr_peer_verified verified;
	if (!own->valid || own->device != e->config.device || e->storage_error ||
		!kr_peer_certificate(own->certificate, own->public_key, server_key,
			kr_get64(own->certificate + 125), &verified) || verified.revision != own->revision ||
		verified.device != own->device || memcmp(verified.owner, own->owner, 16)) { return -1; }
	uint8_t hash[32]; kr_hash(own->payload, sizeof(own->payload), hash);
	struct kr_profile_data data;
	if (memcmp(hash, verified.hash, 32) || !kr_profile_data_decode(own->payload, sizeof(own->payload), &data) ||
		!(data.options & 1) || (e->config.registered && memcmp(e->config.owner, own->owner, 16))) { return -1; }
	struct kr_config next = e->config;
	if (next.profile.policy == KR_MATCH_POLICY && next.profile.revision > own->revision) { return -1; }
	next.registered = 1; memcpy(next.owner, own->owner, 16);
	/* Internal legacy engine token is never a public app credential. */
	if (!e->config.registered) {
		crypto_blake2b(hash, sizeof(hash), own->secret, sizeof(own->secret)); memcpy(next.token, hash, 16);
	}
	memset(&next.profile, 0, sizeof(next.profile)); next.profile.revision = own->revision;
	next.profile.policy = KR_MATCH_POLICY; next.profile.rule = KR_MATCH_RULE;
	if (!own->opportunity_seconds || own->opportunity_seconds != verified.opportunity_seconds) { return -1; }
	next.profile.opportunity_sec = own->opportunity_seconds;
	memcpy(next.profile.snapshot, verified.hash, 16); next.profile.data_len = sizeof(own->payload);
	memcpy(next.profile.data, own->payload, sizeof(own->payload));
	if (memcmp(&e->config, &next, sizeof(next)) &&
		(!e->io.config || e->io.config(e->io.ctx, &next))) { e->storage_error = true; return -1; }
	e->config = next; e->io.synthetic_only = false; e->io.calculate = calculate; e->ready = true;
	p->own = *own; memcpy(p->server_key, server_key, 32); p->active = true; return 0;
}
static bool keys(struct kr_peer_runtime *p, struct kr_peer_session *s)
{
	uint8_t raw[32], material[112] = "TINGLE-PEER-KDF1";
	crypto_x25519(raw, p->own.secret, s->public_key);
	if (zero(raw, sizeof(raw))) { return false; }
	memcpy(material + 16, raw, 32); memcpy(material + 48, p->own.public_key, 32);
	memcpy(material + 80, s->public_key, 32); crypto_blake2b(s->tx_key, 32, material, sizeof(material));
	memcpy(material + 48, s->public_key, 32); memcpy(material + 80, p->own.public_key, 32);
	crypto_blake2b(s->rx_key, 32, material, sizeof(material));
	crypto_wipe(raw, sizeof(raw)); crypto_wipe(material, sizeof(material)); return true;
}
static struct kr_peer_session *find(struct kr_peer_runtime *p, const uint8_t public_key[32])
{
	for (unsigned i = 0; i < KR_PEERS; ++i) {
		if (p->sessions[i].used && !memcmp(p->sessions[i].public_key, public_key, 32)) { return &p->sessions[i]; }
	}
	return NULL;
}
static bool receive(struct kr_peer_runtime *p, struct kr_engine *e, const uint8_t *w, size_t n, uint64_t now)
{
	if (!p->active || !e->ready || n < KR_PEER_HEADER || n > KR_PEER_WIRE_MAX ||
		memcmp(w, "PX\1", 3) || !memcmp(w + 4, p->own.public_key, 32)) { return false; }
	struct kr_peer_session *s = find(p, w + 4);
	if (w[3] == 1) {
		if (n != KR_PEER_HEADER || !zero(w + 36, 42) || zero(w + 4, 32)) { return false; }
		if (s) { return true; }
		/* Unauthenticated discovery can never evict an authenticated session. */
		for (unsigned i = 0; i < KR_PEERS; ++i) { if (!p->sessions[i].used) { s = &p->sessions[i]; break; } }
		if (!s) {
			for (unsigned i = 0; i < KR_PEERS; ++i) {
				unsigned slot = (p->cursor + i) % KR_PEERS;
				if (!p->sessions[slot].authenticated) { s = &p->sessions[slot]; break; }
			}
		}
		if (!s) {
			for (unsigned i = 0; i < KR_PEERS; ++i) {
				struct kr_peer_session *candidate = &p->sessions[i];
				if (now > candidate->last_seen && now - candidate->last_seen > KR_PEER_IDLE_SECONDS &&
					(!s || candidate->last_seen < s->last_seen)) { s = candidate; }
			}
		}
		if (!s) { ++p->overflow; return false; }
		crypto_wipe(s, sizeof(*s)); memcpy(s->public_key, w + 4, 32);
		if (!keys(p, s)) { crypto_wipe(s, sizeof(*s)); return false; }
		s->used = true; s->last_seen = now; return true;
	}
	if (!s || (w[3] != 2 && w[3] != 3) || memcmp(w + 36, p->own.public_key, 32) ||
		n != (size_t)KR_PEER_HEADER + kr_get16(w + 76) + 16) { return false; }
	uint64_t sequence = kr_get64(w + 68);
	if (!sequence || sequence <= s->received) { return false; }
	uint8_t nonce[24] = {0}, plain[KR_PEER_CERT_BYTES];
	size_t length = kr_get16(w + 76);
	if (length > sizeof(plain) || (w[3] == 3 && !s->authenticated)) { return false; }
	kr_put64(nonce + 16, sequence);
	if (crypto_aead_unlock(plain, w + n - 16, s->rx_key, nonce, w, KR_PEER_HEADER,
		w + KR_PEER_HEADER, length)) { return false; }
	bool valid = false;
	if (w[3] == 2 && length == KR_PEER_CERT_BYTES) {
		uint8_t digest[32]; crypto_blake2b(digest, 32, plain, length);
		struct kr_peer_verified verified;
		if (s->authenticated) { valid = !memcmp(digest, s->certificate_hash, 32); }
		else if (kr_peer_certificate(plain, s->public_key, p->server_key, now, &verified) &&
			verified.device != p->own.device && memcmp(verified.owner_ref, p->own.owner_ref, 16)) {
			if (p->cache_certificate && p->cache_certificate(p->application,
				(unsigned)(s - p->sessions), plain, s->public_key)) { crypto_wipe(plain, sizeof(plain)); return false; }
			valid = true; s->remote = verified; memcpy(s->certificate_hash, digest, 32);
			s->authenticated = true; ++p->authenticated;
			/* A verified re-sync replaces session keys, never durable encounter records. */
			for (unsigned i = 0; i < KR_PEERS; ++i) {
				struct kr_peer_session *old = &p->sessions[i];
				if (old != s && old->authenticated && old->remote.device == verified.device) { crypto_wipe(old, sizeof(*old)); }
			}
		}
	} else if (w[3] == 3 && s->authenticated && now < s->remote.valid_until &&
		(!p->allowed || p->allowed(p->application, &s->remote, now))) {
		p->evidence_peer = s->remote.device;
		p->input_peer = &s->remote;
		if (kr_packet_valid(plain, length) && plain[3] == 1 && length == 52 + KR_PROFILE_STORAGE_BYTES && kr_get64(plain + 6) == s->remote.device) {
			struct kr_profile other; uint8_t digest[32];
			if (kr_profile_decode(&other, plain + 14, length - 18)) {
				kr_hash(other.data, other.data_len, digest); struct kr_profile_data decoded;
				valid = other.revision == s->remote.revision && other.policy == KR_MATCH_POLICY &&
					other.rule == KR_MATCH_RULE && other.opportunity_sec == s->remote.opportunity_seconds &&
					!memcmp(other.snapshot, s->remote.hash, 16) && !memcmp(digest, s->remote.hash, 32) &&
					kr_profile_data_decode(other.data, other.data_len, &decoded) && (decoded.options & 1);
			}
		} else if (kr_packet_valid(plain, length) && plain[3] == 2 && length == 75) {
			valid = kr_get64(plain + 6) == s->remote.device && kr_get64(plain + 14) == p->own.device;
		}
		if (valid) {
			if (plain[3] == 1 && p->admit && !p->admit(p->application, &s->remote)) {
				p->evidence_peer = 0; p->input_peer = NULL; crypto_wipe(plain, sizeof(plain)); return false;
			}
			enum kr_result result = kr_receive(e, plain, length, s->remote.device, now);
			if (result == KR_FULL) { ++p->overflow; }
			valid = result == KR_OK; if (valid && plain[3] == 1) { ++p->exchanged; }
		} else {
			/* Only a successfully decrypted authenticated peer can create review
			 * evidence. Unauthenticated radio garbage never consumes flash queues. */
			(void)kr_radio_error(e, s->remote.device, plain, length, KR_BAD_DATA, now);
		}
		p->evidence_peer = 0;
		p->input_peer = NULL;
	}
	if (valid) { s->received = sequence; s->last_seen = now; }
	crypto_wipe(plain, sizeof(plain)); return valid;
}
bool kr_peer_receive(struct kr_peer_runtime *p, struct kr_engine *e, const uint8_t *w, size_t n, uint64_t now)
{
	p->now = now;
	bool valid = receive(p, e, w, n, now); if (!valid) { ++p->rejected; } return valid;
}
static size_t seal(struct kr_peer_runtime *p, struct kr_peer_session *s, uint8_t type,
	const uint8_t *plain, size_t n, uint8_t *out)
{
	if (p->sequence == UINT64_MAX || n + KR_PEER_HEADER + 16 > KR_PEER_WIRE_MAX) { return 0; }
	memcpy(out, "PX\1", 3); out[3] = type; memcpy(out + 4, p->own.public_key, 32);
	memcpy(out + 36, s->public_key, 32); kr_put64(out + 68, ++p->sequence); kr_put16(out + 76, (uint16_t)n);
	uint8_t nonce[24] = {0}; kr_put64(nonce + 16, p->sequence);
	crypto_aead_lock(out + KR_PEER_HEADER, out + KR_PEER_HEADER + n, s->tx_key,
		nonce, out, KR_PEER_HEADER, plain, n); return KR_PEER_HEADER + n + 16;
}
size_t kr_peer_advertisement(struct kr_peer_runtime *p, struct kr_engine *e, uint8_t out[KR_PEER_WIRE_MAX], int *sent_slot)
{
	*sent_slot = -1;
	if (!p->active || !e->ready || e->storage_error) { return 0; }
	for (unsigned n = 0; n <= KR_PEERS; ++n) {
		unsigned index = p->cursor++ % (KR_PEERS + 1);
		if (!index) {
			memset(out, 0, KR_PEER_HEADER); memcpy(out, "PX\1\1", 4);
			memcpy(out + 4, p->own.public_key, 32); return KR_PEER_HEADER;
		}
		struct kr_peer_session *s = &p->sessions[index - 1]; if (!s->used) { continue; }
		if (s->authenticated && (p->now >= s->remote.valid_until ||
			(p->allowed && !p->allowed(p->application, &s->remote, p->now)))) { continue; }
		s->data_next = !s->data_next;
		if (!s->authenticated || !s->data_next) {
			return seal(p, s, 2, p->own.certificate, KR_PEER_CERT_BYTES, out);
		}
		uint8_t packet[KR_WIRE_MAX];
		p->input_peer = &s->remote;
		/* Always interleave PROFILE with controls. A peer may not have received
		 * our profile yet and cannot accept an OFFER before that profile. */
		s->profile_next = !s->profile_next;
		for (unsigned k = 0; !s->profile_next && k <= KR_RECORDS + KR_SUPPRESSIONS; ++k) {
			int slot; size_t length = kr_advertisement(e, &p->control_cursor, packet, &slot);
			if (!length) { break; }
			if (packet[3] == 2 && kr_get64(packet + 14) == s->remote.device &&
				kr_get32(packet + 62) == e->config.profile.revision &&
				kr_get32(packet + 66) == s->remote.revision) {
				*sent_slot = slot; p->input_peer = NULL; return seal(p, s, 3, packet, length, out);
			}
		}
		uint8_t body[8 + 34 + KR_PROFILE_STORAGE_BYTES]; kr_put64(body, p->own.device);
		size_t encoded = kr_profile_encode(&e->config.profile, body + 8);
		size_t length = kr_packet(1, body, encoded + 8, packet);
		p->input_peer = NULL;
		return seal(p, s, 3, packet, length, out);
	}
	return 0;
}

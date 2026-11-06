#include "keyring.h"
#include <string.h>

static bool equal_id(const uint8_t *a, const uint8_t *b)
{
	return memcmp(a, b, 16) == 0;
}

static bool nonzero(const uint8_t *p, size_t n)
{
	uint8_t result = 0;
	for (size_t i = 0; i < n; ++i) {
		result |= p[i];
	}
	return result != 0;
}

static enum kr_result save(struct kr_engine *e, unsigned slot,
	const struct kr_record *r)
{
	if (e->storage_error || e->io.record(e->io.ctx, slot, r)) {
		e->storage_error = true;
		e->ready = false;
		return KR_STORAGE;
	}
	e->records[slot] = *r;
	return KR_OK;
}

void kr_init(struct kr_engine *e, uint64_t device, struct kr_io io)
{
	memset(e, 0, sizeof(*e));
	e->config.format = KR_VERSION;
	e->config.device = device;
	e->io = io;
	e->next_sequence = 1;
}

bool kr_restored_valid(struct kr_engine *e)
{
	if (e->config.format != KR_VERSION || !e->config.device ||
		e->config.profile.data_len > KR_DATA_MAX ||
		(e->config.registered && (!nonzero(e->config.owner, 16) ||
		!nonzero(e->config.token, 16)))) {
		return false;
	}
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &e->records[i];
		if (r->kind != KR_EMPTY && (r->format != KR_VERSION ||
			r->kind > KR_REVIEW || r->phase > KR_COMPLETE ||
			r->peer_len > KR_WIRE_MAX || r->own.data_len > KR_DATA_MAX ||
			r->sequence == UINT32_MAX)) {
			return false;
		}
		if (r->kind == KR_MATCH || r->kind == KR_CONFIRMED) {
			struct kr_profile peer;
			if (r->peer_len < 52 || !kr_packet_valid(r->peer_wire, r->peer_len) ||
				r->peer_wire[3] != 1 || kr_get64(r->peer_wire + 6) != r->peer ||
				!kr_profile_decode(&peer, r->peer_wire + 14, r->peer_len - 18)) {
				return false;
			}
		}
		if (r->kind && r->sequence >= e->next_sequence) {
			e->next_sequence = r->sequence + 1;
		}
	}
	/* Persistent settings are not a trusted wall clock after a power cycle. */
	e->ready = false;
	return true;
}

bool kr_auth(const struct kr_engine *e, const uint8_t token[16])
{
	uint8_t difference = 0;
	for (unsigned i = 0; i < 16; ++i) {
		difference |= token[i] ^ e->config.token[i];
	}
	return e->config.registered && difference == 0;
}

enum kr_result kr_register(struct kr_engine *e, const uint8_t owner[16],
	const uint8_t token[16])
{
	if (!nonzero(owner, 16) || !nonzero(token, 16)) {
		return KR_INVALID;
	}
	if (e->config.registered) {
		return kr_auth(e, token) && equal_id(owner, e->config.owner) ?
			KR_OK : KR_UNAUTHORIZED;
	}
	struct kr_config next = e->config;
	memcpy(next.owner, owner, 16);
	memcpy(next.token, token, 16);
	next.registered = 1;
	if (e->storage_error || e->io.config(e->io.ctx, &next)) {
		return KR_STORAGE;
	}
	e->config = next;
	return KR_OK;
}

static bool synthetic_profile(const struct kr_profile *profile)
{
	if (profile->data_len != KR_DATA_MAX) {
		return false;
	}
	for (unsigned i = 0; i < KR_DATA_MAX; ++i) {
		if (profile->data[i] != (uint8_t)i) {
			return false;
		}
	}
	return true;
}

enum kr_result kr_sync(struct kr_engine *e, const struct kr_profile *p)
{
	uint8_t encoded[KR_PROFILE_BYTES];
	struct kr_profile normalized;
	size_t len = kr_profile_encode(p, encoded);
	if (!e->config.registered || !len || !kr_profile_decode(&normalized, encoded, len) ||
		(e->io.synthetic_only && !synthetic_profile(&normalized))) {
		return KR_INVALID;
	}
	if (p->revision < e->config.profile.revision) {
		return KR_STALE;
	}
	if (p->revision == e->config.profile.revision &&
		memcmp(&normalized, &e->config.profile, sizeof(normalized))) {
		return KR_CONFLICT;
	}
	struct kr_config next = e->config;
	next.profile = normalized;
	if (e->storage_error || e->io.config(e->io.ctx, &next)) {
		e->ready = false;
		return KR_STORAGE;
	}
	e->config = next;
	e->ready = true;
	return KR_OK;
}

unsigned kr_count(const struct kr_engine *e, enum kr_kind kind)
{
	unsigned n = 0;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		n += e->records[i].kind == kind;
	}
	return n;
}

static int existing(const struct kr_engine *e, uint64_t peer)
{
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		if (e->records[i].kind && e->records[i].peer == peer &&
			(!e->io.same_peer || e->io.same_peer(e->io.ctx, i, peer))) {
			return (int)i;
		}
	}
	return -1;
}
unsigned kr_pending(const struct kr_engine *e)
{ return KR_RECORDS - kr_count(e, KR_EMPTY); }
bool kr_can_insert(const struct kr_engine *e, enum kr_kind kind)
{
	unsigned limit = kind == KR_MATCH ? KR_MATCH_LIMIT : kind == KR_REVIEW ? KR_REVIEW_LIMIT : 0;
	if (!limit || e->storage_error || e->next_sequence == UINT32_MAX || !kr_count(e, KR_EMPTY) || kr_count(e, kind) >= limit) { return false; }
	unsigned reserved = 1;
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) { reserved += e->suppressed[i].peer != 0; }
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &e->records[i]; if (!r->kind) { continue; }
		bool covered = false;
		for (unsigned j = 0; j < KR_SUPPRESSIONS; ++j) {
			if (e->suppressed[j].peer && equal_id(e->suppressed[j].id, r->id)) { covered = true; break; }
		}
		reserved += !covered;
	}
	return reserved <= KR_SUPPRESSIONS;
}

static bool suppressed(const struct kr_engine *e, uint64_t peer)
{
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		if (e->suppressed[i].peer == peer && (!e->io.same_peer || e->io.same_peer(e->io.ctx, KR_RECORDS + i, peer))) {
			return true;
		}
	}
	return false;
}

static enum kr_result save_suppression(struct kr_engine *e, unsigned slot,
	const struct kr_suppression *s)
{
	if (e->io.suppression(e->io.ctx, slot, s)) {
		return KR_STORAGE;
	}
	e->suppressed[slot] = *s;
	return KR_OK;
}

static enum kr_result insert(struct kr_engine *e, struct kr_record *r)
{
	if (!kr_can_insert(e, (enum kr_kind)r->kind)) {
		++e->overflow;
		return KR_FULL;
	}
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		if (e->records[i].kind == KR_EMPTY) {
			if (e->io.sequence && e->io.sequence(e->io.ctx, e->next_sequence + 1)) {
				e->storage_error = true; e->ready = false; return KR_STORAGE;
			}
			r->format = KR_VERSION;
			r->sequence = e->next_sequence;
			enum kr_result result = save(e, i, r);
			if (result == KR_OK) {
				++e->next_sequence;
			}
			return result;
		}
	}
	++e->overflow;
	return KR_FULL;
}

enum kr_result kr_radio_error(struct kr_engine *e, uint64_t source,
	const uint8_t *raw, size_t len, enum kr_reason reason, uint64_t now)
{
	if (!e->ready || len > KR_WIRE_MAX) {
		return KR_NOT_READY;
	}
	/* Error keys use a separate namespace; bad bytes are evidence, not identity. */
	uint64_t key = (1ULL << 63) | ((uint64_t)kr_crc32(raw, len) << 16) |
		(source & 0xffff);
	if (e->io.error_key) { key = e->io.error_key(e->io.ctx, source, raw, len) | (1ULL << 63); }
	if (existing(e, key) >= 0 || suppressed(e, key)) {
		return KR_OK;
	}
	struct kr_record r = {0};
	r.kind = KR_REVIEW;
	r.reason = reason;
	r.phase = KR_COMPLETE;
	r.peer = key;
	r.occurred_at = now;
	r.expires_at = now + e->config.profile.opportunity_sec;
	r.own = e->config.profile;
	r.peer_len = (uint16_t)len;
	memcpy(r.peer_wire, raw, len);
	e->io.random(e->io.ctx, r.id, sizeof(r.id));
	return insert(e, &r);
}

static enum kr_result review(struct kr_engine *e, unsigned slot,
	struct kr_record *r, enum kr_reason reason)
{
	if (kr_count(e, KR_REVIEW) >= KR_REVIEW_LIMIT) {
		/* Keep a failed calculation durable without recalculating on every radio event. */
		if (!r->reason) {
			r->reason = reason;
			r->phase = KR_NEW;
			return save(e, slot, r) == KR_OK ? KR_FULL : KR_STORAGE;
		}
		return KR_FULL;
	}
	r->kind = KR_REVIEW;
	r->phase = KR_COMPLETE;
	r->reason = reason;
	if (!nonzero(r->id, 16)) {
		e->io.random(e->io.ctx, r->id, 16);
	}
	return save(e, slot, r);
}

enum kr_decision kr_always_match(void *ctx, const struct kr_profile *a,
	const struct kr_profile *b)
{
	(void)ctx;
	(void)a;
	(void)b;
	return KR_ACCEPT;
}
enum kr_result kr_abandon_candidate(struct kr_engine *e, unsigned slot, enum kr_reason reason)
{
	if (slot >= KR_RECORDS || e->records[slot].kind != KR_MATCH) { return KR_NOT_FOUND; }
	struct kr_record copy = e->records[slot]; return review(e, slot, &copy, reason);
}
bool kr_retired_reason(unsigned reason)
{ return reason == KR_ACCOUNT_DUPLICATE || reason == KR_PEER_OWNERSHIP_CHANGED || reason == KR_PEER_SETTINGS_CHANGED; }

void kr_expire_candidates(struct kr_engine *e, uint64_t now)
{
	if (!e->ready || e->storage_error || !now) {
		return;
	}
	static const struct kr_record empty;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		const struct kr_record *r = &e->records[i];
		/* Retain confirmed records and calculation failures for upload. */
		if (r->kind == KR_MATCH && !r->reason && r->expires_at && now >= r->expires_at &&
			save(e, i, &empty) != KR_OK) {
			break;
		}
	}
}

void kr_drain(struct kr_engine *e)
{
	if (!e->ready || e->storage_error) {
		return;
	}
	/* Sequence order is independent of reused flash slot numbers. */
	uint32_t after = 0;
	for (unsigned n = 0; n < KR_RECORDS; ++n) {
		int slot = -1;
		for (unsigned i = 0; i < KR_RECORDS; ++i) {
			const struct kr_record *r = &e->records[i];
			if (r->kind == KR_MATCH && r->phase == KR_NEW && r->sequence > after &&
				(slot < 0 || r->sequence < e->records[slot].sequence)) {
				slot = (int)i;
			}
		}
		if (slot < 0) {
			break;
		}
		struct kr_record r = e->records[slot];
		after = r.sequence;
		if (r.reason) {
			(void)review(e, (unsigned)slot, &r, (enum kr_reason)r.reason);
			continue;
		}
		struct kr_profile peer;
		if (!kr_profile_decode(&peer, r.peer_wire + 14, r.peer_len - 18)) {
			(void)review(e, (unsigned)slot, &r, KR_BAD_DATA);
			continue;
		}
		if (r.own.revision != e->config.profile.revision) {
			(void)review(e, (unsigned)slot, &r, KR_SETTINGS_CHANGED);
			continue;
		}
		if (r.own.policy != peer.policy || r.own.rule != peer.rule ||
			r.own.opportunity_sec != peer.opportunity_sec) {
			(void)review(e, (unsigned)slot, &r, KR_VERSION_MISMATCH);
			continue;
		}
		if (e->io.candidate_ready && !e->io.candidate_ready(e->io.ctx, (unsigned)slot)) { continue; }
		enum kr_decision decision = e->io.calculate(e->io.ctx, &r.own, &peer);
		if (decision != KR_ACCEPT) {
			(void)review(e, (unsigned)slot, &r, decision == KR_REJECT ?
				KR_ALGORITHM_REJECT : KR_ALGORITHM_ERROR);
			continue;
		}
		r.phase = e->config.device < r.peer ? KR_OFFER : KR_WAIT;
		if (r.phase == KR_OFFER) {
			e->io.random(e->io.ctx, r.id, 16);
		}
		if (save(e, (unsigned)slot, &r) != KR_OK) {
			break;
		}
	}
}

static enum kr_result control(struct kr_engine *e, const uint8_t *p, size_t len,
	uint64_t now)
{
	if (len != 65 || kr_get64(p + 8) != e->config.device) {
		return KR_INVALID;
	}
	uint64_t peer_id = kr_get64(p);
	int slot = existing(e, peer_id);
	if (slot < 0) {
		/* A phone can upload/delete one side before the last over-air receipt arrives. */
		for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
			struct kr_suppression s = e->suppressed[i];
			if (s.peer != peer_id || (e->io.same_peer && !e->io.same_peer(e->io.ctx, KR_RECORDS + i, peer_id)) || !equal_id(s.id, p + 16) ||
				s.occurred_at != kr_get64(p + 32) || s.expires_at != kr_get64(p + 40) ||
				s.peer_revision != kr_get32(p + 56) || s.own_revision != kr_get32(p + 60)) {
				continue;
			}
			if (peer_id < e->config.device && p[64] == KR_COMMIT) {
				s.phase = KR_DONE;
			} else if (peer_id > e->config.device && p[64] == KR_DONE) {
				s.phase = KR_FINAL;
			} else if (peer_id < e->config.device && p[64] == KR_FINAL) {
				s.phase = KR_COMPLETE;
			} else {
				return KR_OK;
			}
			return save_suppression(e, i, &s);
		}
		return KR_NOT_FOUND;
	}
	struct kr_record r = e->records[slot];
	if (r.kind == KR_REVIEW || !nonzero(p + 16, 16)) {
		return KR_CONFLICT;
	}
	struct kr_profile peer;
	if (!kr_profile_decode(&peer, r.peer_wire + 14, r.peer_len - 18) ||
		kr_get32(p + 56) != peer.revision || kr_get32(p + 60) != r.own.revision) {
		return KR_CONFLICT;
	}
	if (r.kind == KR_MATCH && r.own.revision != e->config.profile.revision) {
		return review(e, (unsigned)slot, &r, KR_SETTINGS_CHANGED);
	}
	bool coordinator = e->config.device < peer_id;
	uint8_t phase = p[64];
	if (phase == KR_OFFER && !coordinator && r.phase == KR_WAIT) {
		uint64_t occurred = kr_get64(p + 32);
		uint64_t expires = kr_get64(p + 40);
		if (!occurred || (occurred > now && occurred - now > 300) || expires < occurred || expires - occurred != r.own.opportunity_sec ||
			now >= expires) {
			return KR_INVALID;
		}
		memcpy(r.id, p + 16, 16);
		r.occurred_at = occurred;
		r.expires_at = expires;
		r.phase = KR_PREPARED;
		return save(e, (unsigned)slot, &r);
	}
	if (!equal_id(r.id, p + 16) || r.occurred_at != kr_get64(p + 32) ||
		r.expires_at != kr_get64(p + 40)) {
		return KR_CONFLICT;
	}
	bool confirm = false;
	if (phase == KR_PREPARED && coordinator && r.phase == KR_OFFER) {
		r.phase = KR_COMMIT;
	} else if (phase == KR_COMMIT && !coordinator && r.phase == KR_PREPARED) {
		r.phase = KR_DONE;
		confirm = true;
	} else if (phase == KR_DONE && coordinator && r.phase == KR_COMMIT) {
		r.phase = KR_FINAL;
		confirm = true;
	} else if (phase == KR_DONE && coordinator && r.phase == KR_COMPLETE) {
		r.phase = KR_FINAL; /* Recover a lost FINAL without another feedback. */
	} else if (phase == KR_FINAL && !coordinator && r.phase == KR_DONE) {
		r.phase = KR_COMPLETE;
	} else {
		return KR_OK;
	}
	if (confirm) {
		if (now >= r.expires_at) {
			return KR_STALE;
		}
		if (kr_count(e, KR_CONFIRMED) >= KR_CONFIRMED_LIMIT) {
			return KR_FULL;
		}
		r.kind = KR_CONFIRMED;
		/* Claim is durable BEFORE hardware output: recovery never repeats output. */
		r.feedback_claimed = 1;
	}
	enum kr_result result = save(e, (unsigned)slot, &r);
	if (result == KR_OK && confirm && e->io.feedback) {
		e->io.feedback(e->io.ctx, r.id);
	}
	return result;
}

enum kr_result kr_receive(struct kr_engine *e, const uint8_t *wire, size_t len,
	uint64_t source, uint64_t now)
{
	if (!e->ready || !now || e->storage_error) {
		return KR_NOT_READY;
	}
	if (!kr_packet_valid(wire, len)) {
		return kr_radio_error(e, source, wire, len, KR_BAD_DATA, now);
	}
	if (wire[3] == 2) {
		return control(e, wire + 6, len - 10, now);
	}
	struct kr_profile peer;
	if (wire[3] != 1 || len < 52 ||
		!kr_profile_decode(&peer, wire + 14, len - 18)) {
		return kr_radio_error(e, source, wire, len, KR_BAD_DATA, now);
	}
	uint64_t peer_id = kr_get64(wire + 6);
	if (!peer_id || peer_id >> 63 || peer_id == e->config.device) {
		return KR_INVALID;
	}
	if (existing(e, peer_id) >= 0 || suppressed(e, peer_id)) {
		return KR_OK;
	}
	struct kr_record r = {0};
	r.kind = KR_MATCH;
	r.peer = peer_id;
	r.occurred_at = now;
	r.expires_at = now + e->config.profile.opportunity_sec;
	r.own = e->config.profile;
	r.peer_len = (uint16_t)len;
	memcpy(r.peer_wire, wire, len);
	return insert(e, &r);
}

size_t kr_advertisement(struct kr_engine *e, unsigned *cursor, uint8_t *out,
	int *sent_slot)
{
	*sent_slot = -1;
	if (!e->ready || e->storage_error) {
		return 0;
	}
	uint8_t body[KR_PROFILE_BYTES + 8];
	/* Round robin over profile + pending handshakes. No queue delay or score sorting. */
	for (unsigned n = 0; n <= KR_RECORDS + KR_SUPPRESSIONS; ++n) {
		unsigned index = (*cursor)++ % (KR_RECORDS + KR_SUPPRESSIONS + 1);
		if (index == 0) {
			kr_put64(body, e->config.device);
			size_t len = kr_profile_encode(&e->config.profile, body + 8);
			return kr_packet(1, body, len + 8, out);
		}
		if (index > KR_RECORDS) {
			const struct kr_suppression *s = &e->suppressed[index - KR_RECORDS - 1];
			if (!s->peer || (e->io.same_peer && !e->io.same_peer(e->io.ctx, index - 1, s->peer)) || (s->phase != KR_DONE && s->phase != KR_FINAL)) {
				continue;
			}
			kr_put64(body, e->config.device);
			kr_put64(body + 8, s->peer);
			memcpy(body + 16, s->id, 16);
			kr_put64(body + 32, s->occurred_at);
			kr_put64(body + 40, s->expires_at);
			kr_put64(body + 48, 0);
			kr_put32(body + 56, s->own_revision);
			kr_put32(body + 60, s->peer_revision);
			body[64] = s->phase;
			*sent_slot = s->phase == KR_FINAL ? (int)index - 1 : -1;
			return kr_packet(2, body, 65, out);
		}
		const struct kr_record *r = &e->records[index - 1];
		if (!r->kind || r->kind == KR_REVIEW || (e->io.same_peer && !e->io.same_peer(e->io.ctx, index - 1, r->peer)) || r->phase < KR_OFFER ||
			r->phase == KR_COMPLETE) {
			continue;
		}
		struct kr_profile peer;
		if (!kr_profile_decode(&peer, r->peer_wire + 14, r->peer_len - 18)) {
			continue;
		}
		kr_put64(body, e->config.device);
		kr_put64(body + 8, r->peer);
		memcpy(body + 16, r->id, 16);
		kr_put64(body + 32, r->occurred_at);
		kr_put64(body + 40, r->expires_at);
		kr_put64(body + 48, 0); /* Reserved; must stay zero in version 1. */
		kr_put32(body + 56, r->own.revision);
		kr_put32(body + 60, peer.revision);
		body[64] = r->phase;
		*sent_slot = r->phase == KR_FINAL ? (int)index - 1 : -1;
		return kr_packet(2, body, 65, out);
	}
	return 0;
}

void kr_sent(struct kr_engine *e, int slot)
{
	if (slot >= 0 && slot < KR_RECORDS && e->records[slot].phase == KR_FINAL) {
		struct kr_record r = e->records[slot];
		r.phase = KR_COMPLETE;
		(void)save(e, (unsigned)slot, &r);
	} else if (slot >= KR_RECORDS && slot < KR_RECORDS + KR_SUPPRESSIONS &&
		e->suppressed[slot - KR_RECORDS].phase == KR_FINAL) {
		struct kr_suppression s = e->suppressed[slot - KR_RECORDS];
		s.phase = KR_COMPLETE;
		(void)save_suppression(e, (unsigned)slot - KR_RECORDS, &s);
	}
}

bool kr_sent_guarded(struct kr_engine *e, int slot, uint32_t sequence, const uint8_t id[16])
{
	bool same = false;
	if (slot >= 0 && slot < KR_RECORDS) {
		const struct kr_record *r = &e->records[slot]; same = r->kind && r->sequence == sequence && equal_id(r->id, id);
	} else if (slot >= KR_RECORDS && slot < KR_RECORDS + KR_SUPPRESSIONS) {
		const struct kr_suppression *s = &e->suppressed[slot - KR_RECORDS]; same = s->peer && equal_id(s->id, id);
	}
	if (!same) { return false; } kr_sent(e, slot); return !e->storage_error;
}
enum kr_result kr_ack(struct kr_engine *e, const uint8_t id[16])
{
	int slot = -1;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		if ((e->records[i].kind == KR_CONFIRMED || e->records[i].kind == KR_REVIEW) &&
			equal_id(e->records[i].id, id)) {
			slot = (int)i;
			break;
		}
	}
	int empty = -1;
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		if (equal_id(e->suppressed[i].id, id) && e->suppressed[i].peer) {
			if (slot < 0) {
				return KR_OK;
			}
			empty = (int)i;
			break;
		}
		if (!e->suppressed[i].peer && empty < 0) {
			empty = (int)i;
		}
	}
	if (slot < 0) {
		return KR_NOT_FOUND;
	}
	if (empty < 0) {
		return KR_FULL;
	}
	const struct kr_record *r = &e->records[slot];
	struct kr_suppression s = {
		.peer = r->peer, .occurred_at = r->occurred_at, .expires_at = r->expires_at,
		.own_revision = r->own.revision, .phase = r->phase,
	};
	if (r->kind == KR_CONFIRMED) {
		struct kr_profile peer;
		if (!kr_profile_decode(&peer, r->peer_wire + 14, r->peer_len - 18)) {
			return KR_INVALID;
		}
		s.peer_revision = peer.revision;
	}
	memcpy(s.id, id, 16);
	/* Suppression first, raw deletion second; either crash ordering is safe. */
	if (save_suppression(e, (unsigned)empty, &s) != KR_OK) {
		return KR_STORAGE;
	}
	struct kr_record cleared = {0};
	return save(e, (unsigned)slot, &cleared);
}

enum kr_result kr_release(struct kr_engine *e, const uint8_t id[16])
{
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		if (equal_id(e->suppressed[i].id, id) && e->suppressed[i].peer) {
			/* Never release while a failed raw deletion still needs recovery. */
			for (unsigned n = 0; n < KR_RECORDS; ++n) {
				if (e->records[n].kind && equal_id(e->records[n].id, id)) { return KR_CONFLICT; }
			}
			if (e->suppressed[i].phase != KR_COMPLETE) {
				return KR_NOT_READY;
			}
			struct kr_suppression cleared = {0};
			if (e->io.suppression(e->io.ctx, i, &cleared)) {
				return KR_STORAGE;
			}
			e->suppressed[i] = cleared;
			return KR_OK;
		}
	}
	return KR_NOT_FOUND;
}

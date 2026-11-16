#include "keyring.h"
#include "phone.h"
#include "record_restore.h"
#include <errno.h>
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <assert.h>
#include <stdio.h>
#include <string.h>

struct disk {
	struct kr_config config;
	struct kr_record records[KR_RECORDS];
	struct kr_suppression suppressed[KR_SUPPRESSIONS];
	unsigned random;
	unsigned feedback;
	unsigned calculations;
	unsigned writes;
	uint32_t order[32];
	bool fail;
	enum kr_decision decision;
};

#define TEST_DEVICES 10
static struct disk disks[TEST_DEVICES];
static struct kr_engine engines[TEST_DEVICES];
static uint8_t packet[KR_WIRE_MAX];
static uint8_t owner[16] = {1};
static uint8_t token[16] = {2};

static int config_write(void *ctx, const struct kr_config *config)
{
	struct disk *d = ctx;
	if (d->fail) {
		return -1;
	}
	d->config = *config;
	return 0;
}

static int record_write(void *ctx, unsigned slot, const struct kr_record *record)
{
	struct disk *d = ctx;
	if (d->fail) {
		return -1;
	}
	d->records[slot] = *record;
	++d->writes;
	return 0;
}

static int suppression_write(void *ctx, unsigned slot, const struct kr_suppression *s)
{
	struct disk *d = ctx;
	if (d->fail) {
		return -1;
	}
	d->suppressed[slot] = *s;
	++d->writes;
	return 0;
}

static void random_bytes(void *ctx, uint8_t *out, size_t len)
{
	struct disk *d = ctx;
	memset(out, (int)++d->random, len);
}

static void feedback(void *ctx, const struct kr_record *record, const struct kr_profile *peer)
{
	(void)peer;
	struct disk *d = ctx;
	bool found = false;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		if (d->records[i].kind == KR_CONFIRMED && !memcmp(d->records[i].id, record->id, 16)) {
			assert(d->records[i].feedback_claimed);
			found = true;
		}
	}
	assert(found);
	++d->feedback;
}

static enum kr_decision calculate(void *ctx, const struct kr_profile *a,
	const struct kr_profile *b)
{
	struct disk *d = ctx;
	(void)a;
	assert(d->calculations < 32);
	d->order[d->calculations++] = b->revision;
	return d->decision;
}

static struct kr_profile profile(uint32_t revision)
{
	struct kr_profile p = {0};
	p.revision = revision;
	p.policy = 1;
	p.rule = 1;
	p.opportunity_sec = 604800; /* Explicit fixture input, no engine policy constant. */
	p.data_len = KR_DATA_MAX;
	memset(p.snapshot, (int)revision, 16);
	for (unsigned i = 0; i < p.data_len; ++i) {
		p.data[i] = (uint8_t)i;
	}
	return p;
}

static void setup(unsigned index)
{
	struct disk *d = &disks[index];
	memset(d, 0, sizeof(*d));
	d->random = 10 * index;
	struct kr_io io = {.config = config_write, .record = record_write,
		.suppression = suppression_write, .random = random_bytes,
		.feedback = feedback, .calculate = calculate, .ctx = d};
	kr_init(&engines[index], index + 1, io);
	assert(kr_register(&engines[index], owner, token) == KR_OK);
	struct kr_profile p = profile(1);
	assert(kr_sync(&engines[index], &p) == KR_OK);
}

static void reboot(unsigned index)
{
	struct kr_io io = engines[index].io;
	kr_init(&engines[index], index + 1, io);
	engines[index].config = disks[index].config;
	memcpy(engines[index].records, disks[index].records, sizeof(disks[index].records));
	memcpy(engines[index].suppressed, disks[index].suppressed, sizeof(disks[index].suppressed));
	assert(kr_restored_valid(&engines[index]));
	assert(!engines[index].ready);
	struct kr_profile p = engines[index].config.profile;
	assert(kr_sync(&engines[index], &p) == KR_OK);
}

static size_t peer_packet(uint64_t device, uint32_t revision)
{
	uint8_t body[KR_PROFILE_BYTES + 8];
	struct kr_profile p = profile(revision);
	kr_put64(body, device);
	size_t len = kr_profile_encode(&p, body + 8);
	return kr_packet(1, body, len + 8, packet);
}

static void exchange(unsigned rounds, bool drop_every_third)
{
	unsigned cursors[2] = {0};
	for (unsigned n = 0; n < rounds; ++n) {
		for (unsigned a = 0; a < 2; ++a) {
			kr_drain(&engines[a]);
			int slot;
			size_t len = kr_advertisement(&engines[a], &cursors[a], packet, &slot);
			assert(len > 0);
			if (!drop_every_third || n % 3) {
				(void)kr_receive(&engines[1 - a], packet, len, a + 1, 1000 + a);
			}
			kr_sent(&engines[a], slot);
		}
	}
}

static void test_codec(void)
{
	assert(kr_crc32((const uint8_t *)"123456789", 9) == 0xcbf43926);
	size_t len = peer_packet(8, 1);
	assert(len > 1000 && len < 1650);
	assert(kr_packet_valid(packet, len));
	for (size_t i = 0; i < len; ++i) {
		assert(!kr_packet_valid(packet, i));
	}
	packet[50] ^= 1;
	assert(!kr_packet_valid(packet, len));
	struct kr_profile p;
	uint8_t invalid[34] = {0};
	kr_put16(invalid + 32, 65535);
	assert(!kr_profile_decode(&p, invalid, sizeof(invalid)));
}

static void test_registration_and_sync(void)
{
	setup(0);
	setup(1);
	assert(!memcmp(engines[0].config.owner, engines[1].config.owner, 16));
	assert(engines[0].config.device != engines[1].config.device);
	assert(kr_register(&engines[0], owner, token) == KR_OK);
	uint8_t other[16] = {99};
	assert(kr_register(&engines[0], owner, other) == KR_UNAUTHORIZED);
	assert(!kr_auth(&engines[0], other));
	struct kr_profile p = profile(2);
	disks[0].fail = true;
	assert(kr_sync(&engines[0], &p) == KR_STORAGE);
	assert(engines[0].config.profile.revision == 1);
	disks[0].fail = false;
	assert(kr_sync(&engines[0], &p) == KR_OK);
	p.data[0] ^= 1;
	assert(kr_sync(&engines[0], &p) == KR_CONFLICT);
	p = profile(1);
	assert(kr_sync(&engines[0], &p) == KR_STALE);
	reboot(0);
}

static void test_shared_event_loss_restart_and_receipt(void)
{
	setup(0);
	setup(1);
	exchange(2, false);
	reboot(0);
	reboot(1);
	exchange(80, true);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 1);
	assert(kr_count(&engines[1], KR_CONFIRMED) == 1);
	assert(kr_count(&engines[0], KR_MATCH) == 0);
	assert(!memcmp(engines[0].records[0].id, engines[1].records[0].id, 16));
	assert(engines[0].records[0].occurred_at == engines[1].records[0].occurred_at);
	assert(engines[0].records[0].expires_at == engines[1].records[0].expires_at);
	assert(disks[0].feedback == 1 && disks[1].feedback == 1);
	reboot(0);
	exchange(30, false);
	assert(disks[0].feedback == 1);
	uint8_t id[16];
	memcpy(id, engines[0].records[0].id, 16);
	assert(kr_ack(&engines[0], id) == KR_OK);
	assert(kr_ack(&engines[0], id) == KR_OK);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 0);
	reboot(0);
	exchange(30, false);
	assert(kr_count(&engines[0], KR_MATCH) == 0);
	assert(kr_release(&engines[0], id) == KR_OK);
	size_t len = peer_packet(2, 1);
	assert(kr_receive(&engines[0], packet, len, 2, 2000) == KR_OK);
	assert(kr_count(&engines[0], KR_MATCH) == 1);
}

static void test_immediate_fifo_and_failure_queues(void)
{
	setup(0);
	for (unsigned i = 0; i < KR_MATCH_LIMIT; ++i) {
		size_t len = peer_packet(i + 10, i + 1);
		assert(kr_receive(&engines[0], packet, len, i + 10, 1000) == KR_OK);
	}
	kr_drain(&engines[0]);
	assert(disks[0].calculations == KR_MATCH_LIMIT);
	for (unsigned i = 0; i < KR_MATCH_LIMIT; ++i) {
		assert(disks[0].order[i] == i + 1);
		assert(engines[0].records[i].phase == KR_OFFER);
	}
	size_t len = peer_packet(999, 1);
	assert(kr_receive(&engines[0], packet, len, 999, 1000) == KR_FULL);
	assert(engines[0].overflow == 1);
	setup(0);
	disks[0].decision = KR_CALC_ERROR;
	assert(kr_receive(&engines[0], packet, len, 999, 1000) == KR_OK);
	kr_drain(&engines[0]);
	assert(kr_count(&engines[0], KR_REVIEW) == 1);
	assert(engines[0].records[0].reason == KR_ALGORITHM_ERROR);
	assert(engines[0].records[0].own.data_len == KR_DATA_MAX);
	packet[50] ^= 0x80;
	assert(kr_receive(&engines[0], packet, len, 998, 1000) == KR_OK);
	assert(kr_count(&engines[0], KR_REVIEW) == 2);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 0);
	assert(kr_receive(&engines[0], packet, len, 998, 1000) == KR_OK);
	assert(kr_count(&engines[0], KR_REVIEW) == 2);
	reboot(0);
	assert(kr_count(&engines[0], KR_REVIEW) == 2);
}

static void test_storage_failure(void)
{
	setup(0);
	size_t len = peer_packet(2, 1);
	disks[0].fail = true;
	assert(kr_receive(&engines[0], packet, len, 2, 1000) == KR_STORAGE);
	assert(!engines[0].ready && engines[0].storage_error);
	assert(kr_count(&engines[0], KR_MATCH) == 0);
	assert(disks[0].feedback == 0);
	disks[0].fail = false;
	reboot(0);
	assert(kr_count(&engines[0], KR_MATCH) == 0);
}

static void test_phone_frames(void)
{
	setup(0);
	setup(1);
	exchange(50, false);
	uint8_t body[64] = {0};
	uint8_t request[KR_BINARY_FRAME_MAX];
	uint8_t reply[KR_BINARY_FRAME_MAX];
	uint64_t utc;
	size_t len = kr_phone_frame(KR_STATUS, 7, body, 0, request);
	size_t response = kr_phone_process(&engines[0], request, len, reply, &utc);
	assert(response == 43 && reply[10] == KR_OK && kr_get32(reply + 4) == 7);
	memcpy(body, token, 16);
	body[16] = KR_CONFIRMED;
	len = kr_phone_frame(KR_READ_RECORD, 8, body, 21, request);
	response = kr_phone_process(&engines[0], request, len, reply, &utc);
	assert(reply[10] == KR_OK && response > 2000);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 1); /* Reading is not server storage. */
	uint32_t record_crc = kr_crc32(reply + 11, response - 15);
	uint8_t id[16];
	memcpy(id, reply + 19, 16);
	kr_put64(body + 16, 999);
	memcpy(body + 24, id, 16);
	kr_put32(body + 40, record_crc);
	body[44] = 1;
	len = kr_phone_frame(KR_LAB_STORED_RECEIPT, 9, body, 45, request);
	(void)kr_phone_process(&engines[0], request, len, reply, &utc);
	assert(reply[10] != KR_OK);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 1);
	kr_put64(body + 16, 1);
	len = kr_phone_frame(KR_LAB_STORED_RECEIPT, 10, body, 45, request);
	(void)kr_phone_process(&engines[0], request, len, reply, &utc);
	assert(reply[10] == KR_OK);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 0);
	request[len - 1] ^= 1;
	assert(kr_phone_process(&engines[0], request, len, reply, &utc) == 0);
}

static void test_every_handshake_restart(void)
{
	for (unsigned cut = 0; cut < 12; ++cut) {
		setup(0);
		setup(1);
		exchange(cut, false);
		reboot(0);
		reboot(1);
		exchange(80, true);
		assert(kr_count(&engines[0], KR_CONFIRMED) == 1);
		assert(kr_count(&engines[1], KR_CONFIRMED) == 1);
		assert(!memcmp(engines[0].records[0].id, engines[1].records[0].id, 16));
		assert(disks[0].feedback == 1 && disks[1].feedback == 1);
	}
}

static void test_many_devices(void)
{
	unsigned cursors[TEST_DEVICES] = {0};
	for (unsigned i = 0; i < TEST_DEVICES; ++i) {
		setup(i);
	}
	for (unsigned round = 0; round < 500; ++round) {
		/* Seven devices enter an area already occupied by three. */
		unsigned active = round < 40 ? 3 : TEST_DEVICES;
		for (unsigned source = 0; source < active; ++source) {
			kr_drain(&engines[source]);
			int sent;
			size_t len = kr_advertisement(&engines[source], &cursors[source], packet, &sent);
			for (unsigned target = 0; target < active; ++target) {
				if (source != target && (round + 3 * source + target) % 7) {
					(void)kr_receive(&engines[target], packet, len, source + 1, 1000 + source);
				}
			}
			kr_sent(&engines[source], sent);
		}
	}
	for (unsigned i = 0; i < TEST_DEVICES; ++i) {
		assert(kr_count(&engines[i], KR_CONFIRMED) == TEST_DEVICES - 1);
		assert(disks[i].feedback == TEST_DEVICES - 1);
		for (unsigned slot = 0; slot < KR_RECORDS; ++slot) {
			const struct kr_record *r = &engines[i].records[slot];
			if (r->kind != KR_CONFIRMED) {
				continue;
			}
			assert(r->peer > 0 && r->peer <= TEST_DEVICES && r->peer != i + 1);
			const struct kr_engine *peer = &engines[r->peer - 1];
			unsigned matching = 0;
			for (unsigned p = 0; p < KR_RECORDS; ++p) {
				const struct kr_record *other = &peer->records[p];
				matching += other->kind == KR_CONFIRMED && other->peer == i + 1 &&
					!memcmp(other->id, r->id, sizeof(r->id));
			}
			assert(matching == 1);
		}
	}
}

static void test_expired_candidates_free_capacity(void)
{
	setup(0);
	setup(1);
	exchange(50, false);
	for (unsigned i = 0; i < KR_MATCH_LIMIT; ++i) {
		size_t len = peer_packet(i + 3, 1);
		assert(kr_receive(&engines[0], packet, len, i + 3, 2000) == KR_OK);
	}
	kr_drain(&engines[0]);
	uint64_t expires = 2000 + engines[0].config.profile.opportunity_sec;
	size_t len = peer_packet(KR_MATCH_LIMIT + 3, 1);
	kr_expire_candidates(&engines[0], 0);
	kr_expire_candidates(&engines[0], expires - 1);
	assert(kr_receive(&engines[0], packet, len, KR_MATCH_LIMIT + 3, expires - 1) == KR_FULL);
	kr_expire_candidates(&engines[0], expires);
	assert(kr_count(&engines[0], KR_MATCH) == 0);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 1);
	assert(kr_receive(&engines[0], packet, len, KR_MATCH_LIMIT + 3, expires) == KR_OK);
	reboot(0);
	assert(kr_count(&engines[0], KR_MATCH) == 1);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 1);
}

static void test_upload_before_final_radio_receipt(void)
{
	setup(0);
	setup(1);
	unsigned cursors[2] = {0};
	bool uploaded = false;
	uint8_t saved_id[16] = {0};
	for (unsigned round = 0; round < 80; ++round) {
		for (unsigned source = 0; source < 2; ++source) {
			kr_drain(&engines[source]);
			int sent;
			size_t len = kr_advertisement(&engines[source], &cursors[source], packet, &sent);
			(void)kr_receive(&engines[1 - source], packet, len, source + 1, 1000 + source);
			kr_sent(&engines[source], sent);
			if (!uploaded && kr_count(&engines[1], KR_CONFIRMED) == 1) {
				assert(kr_count(&engines[0], KR_CONFIRMED) == 0);
				memcpy(saved_id, engines[1].records[0].id, 16);
				assert(kr_ack(&engines[1], saved_id) == KR_OK);
				reboot(1);
				uploaded = true;
			}
		}
	}
	assert(uploaded);
	assert(kr_count(&engines[0], KR_CONFIRMED) == 1);
	assert(kr_count(&engines[1], KR_CONFIRMED) == 0);
	assert(!memcmp(engines[0].records[0].id, saved_id, 16));
	assert(disks[0].feedback == 1 && disks[1].feedback == 1);
	assert(engines[1].suppressed[0].phase == KR_COMPLETE);
}

static void test_review_backpressure_and_corrupt_restore(void)
{
	setup(0);
	for (unsigned i = 0; i < KR_REVIEW_LIMIT; ++i) {
		uint8_t bad = (uint8_t)i;
		assert(kr_radio_error(&engines[0], i, &bad, 1, KR_BAD_DATA, 1000) == KR_OK);
	}
	disks[0].decision = KR_CALC_ERROR;
	size_t len = peer_packet(2, 1);
	assert(kr_receive(&engines[0], packet, len, 2, 1000) == KR_OK);
	kr_drain(&engines[0]);
	kr_drain(&engines[0]);
	assert(disks[0].calculations == 1);
	assert(kr_count(&engines[0], KR_MATCH) == 1);
	assert(kr_ack(&engines[0], engines[0].records[0].id) == KR_OK);
	kr_drain(&engines[0]);
	assert(kr_count(&engines[0], KR_MATCH) == 0);
	assert(kr_count(&engines[0], KR_REVIEW) == KR_REVIEW_LIMIT);
	setup(0);
	len = peer_packet(2, 1);
	assert(kr_receive(&engines[0], packet, len, 2, 1000) == KR_OK);
	engines[0].records[0].peer_len = 1;
	assert(!kr_restored_valid(&engines[0]));
}

static void test_sync_fixture(const char *path)
{
	FILE *file = fopen(path, "r");
	assert(file);
	uint8_t frame[KR_BINARY_FRAME_MAX];
	size_t len = 0;
	unsigned value;
	while (fscanf(file, "%2x", &value) == 1) {
		assert(len < sizeof(frame));
		frame[len++] = (uint8_t)value;
	}
	fclose(file);
	setup(0);
	engines[0].io.synthetic_only = true;
	uint8_t response[KR_BINARY_FRAME_MAX];
	uint64_t utc;
	assert(kr_phone_process(&engines[0], frame, len, response, &utc) > 0);
	assert(response[10] == KR_OK);
	assert(utc == 1700000000);
	assert(engines[0].config.profile.revision == 7);
	assert(engines[0].config.profile.data_len == 1024);
	for (unsigned i = 0; i < 1024; ++i) {
		assert(engines[0].config.profile.data[i] == (uint8_t)i);
	}
	struct kr_profile arbitrary = engines[0].config.profile;
	++arbitrary.revision;
	arbitrary.data[0] ^= 1;
	assert(kr_sync(&engines[0], &arbitrary) == KR_INVALID);
	assert(engines[0].config.profile.revision == 7);
	for (size_t i = 0; i < len; ++i) {
		frame[i] ^= 1;
		assert(kr_phone_process(&engines[0], frame, len, response, &utc) == 0);
		frame[i] ^= 1;
	}
}

static int broken_id;
static int read_test_record(void *ctx, uint16_t id, void *out, size_t length)
{
	struct disk *d = ctx;
	if (id == 1) { assert(length == sizeof(d->config)); memcpy(out, &d->config, length); }
	else if (id >= 100 && id < 132) {
		if (!d->records[id - 100].kind) { return -ENOENT; }
		memcpy(out, &d->records[id - 100], length);
	} else if (id >= 200 && id < 264) {
		if (!d->suppressed[id - 200].peer && id != broken_id) { return -ENOENT; }
		memcpy(out, &d->suppressed[id - 200], length);
	} else { return -ENOENT; }
	/* Real NVS checks CRC AFTER it has already copied the corrupt bytes. */
	return id == broken_id ? -EIO : (int)length;
}
static void test_read_only_quarantine(void)
{
	setup(0);
	for (unsigned i = 0; i < 3; ++i) {
		size_t n = peer_packet(10 + i, i + 1);
		assert(kr_receive(&engines[0], packet, n, 10 + i, 1000) == KR_OK);
	}
	struct disk original = disks[0];
	struct kr_io io = engines[0].io;
	kr_init(&engines[0], 1, io); broken_id = 100;
	assert(!kr_records_restore(&engines[0], read_test_record, &disks[0]));
	assert(kr_quarantine_count(&engines[0]) == 1 && !engines[0].records[0].kind);
	assert(kr_count(&engines[0], KR_MATCH) == 2 && kr_pending(&engines[0]) == 3);
	assert(!memcmp(&original, &disks[0], sizeof(original)));
	assert(engines[0].next_sequence == 4 && !engines[0].storage_error);
	struct kr_profile p = engines[0].config.profile; assert(kr_sync(&engines[0], &p) == KR_OK);
	size_t n = peer_packet(99, 4); assert(kr_receive(&engines[0], packet, n, 99, 1000) == KR_OK);
	assert(engines[0].records[3].peer == 99 && !engines[0].records[0].kind);
	assert(!memcmp(&original.records[0], &disks[0].records[0], sizeof(struct kr_record)));
	broken_id = 0; disks[0].records[1].phase = 255;
	kr_init(&engines[0], 1, io);
	assert(!kr_records_restore(&engines[0], read_test_record, &disks[0]));
	assert(kr_quarantined(&engines[0], 1));
	broken_id = 200; assert(kr_records_restore(&engines[0], read_test_record, &disks[0]) == -EILSEQ);
	broken_id = 1; assert(kr_records_restore(&engines[0], read_test_record, &disks[0]) == -EILSEQ);
	broken_id = 0; disks[0].config.device = 99;
	assert(kr_records_restore(&engines[0], read_test_record, &disks[0]) == -EILSEQ);
}
static void test_completed_radio_retry_without_writes(void)
{
	setup(0); setup(1); exchange(80, false);
	assert(engines[0].records[0].phase == KR_COMPLETE);
	uint8_t body[65] = {0}, done[100], reply[KR_WIRE_MAX], id[16];
	const struct kr_record *r = &engines[1].records[0];
	kr_put64(body, 2); kr_put64(body + 8, 1); memcpy(body + 16, r->id, 16);
	kr_put64(body + 32, r->occurred_at); kr_put64(body + 40, r->expires_at);
	kr_put32(body + 56, 1); kr_put32(body + 60, 1); body[64] = KR_DONE;
	size_t n = kr_packet(2, body, sizeof(body), done); memcpy(id, r->id, 16);
	for (unsigned stage = 0; stage < 2; ++stage) {
		if (stage) { assert(kr_ack(&engines[0], id) == KR_OK); reboot(0); }
		unsigned writes = disks[0].writes, alerts = disks[0].feedback;
		for (unsigned i = 0; i < 1000; ++i) {
			assert(kr_receive(&engines[0], done, n, 2, 1000) == KR_OK);
			unsigned cursor = 0; int sent = -1; bool found = false;
			for (unsigned j = 0; j < KR_RECORDS + KR_SUPPRESSIONS + 1; ++j) {
				size_t size = kr_advertisement(&engines[0], &cursor, reply, &sent);
				if (size && reply[3] == 2) { assert(reply[70] == KR_FINAL); found = true; kr_sent(&engines[0], sent); break; }
			}
			assert(found && disks[0].writes == writes && disks[0].feedback == alerts);
		}
	}
}
int main(int argc, char **argv)
{
	test_codec();
	test_read_only_quarantine();
	test_completed_radio_retry_without_writes();
	test_registration_and_sync();
	test_shared_event_loss_restart_and_receipt();
	test_immediate_fifo_and_failure_queues();
	test_storage_failure();
	test_phone_frames();
	test_every_handshake_restart();
	test_many_devices();
	test_expired_candidates_free_capacity();
	test_upload_before_final_radio_receipt();
	test_review_backpressure_and_corrupt_restore();
	if (argc > 1) {
		test_sync_fixture(argv[1]);
	}
	printf("PASS: binary bounds/CRC, multi-device registration, durable sync, bilateral event, "
		"12 restart cuts, 10 devices with packet loss and staggered arrival, expiry, "
		"immediate FIFO, three queues, failure evidence, "
		"backpressure, corrupt storage, durable receipts, shared wire fixture\n");
	return 0;
}

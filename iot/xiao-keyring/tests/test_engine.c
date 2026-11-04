#include "keyring.h"
#include "phone.h"
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
	uint32_t order[32];
	bool fail;
	enum kr_decision decision;
};

static struct disk disks[3];
static struct kr_engine engines[3];
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
	return 0;
}

static int suppression_write(void *ctx, unsigned slot, const struct kr_suppression *s)
{
	struct disk *d = ctx;
	if (d->fail) {
		return -1;
	}
	d->suppressed[slot] = *s;
	return 0;
}

static void random_bytes(void *ctx, uint8_t *out, size_t len)
{
	struct disk *d = ctx;
	memset(out, (int)++d->random, len);
}

static void feedback(void *ctx, const uint8_t id[16])
{
	struct disk *d = ctx;
	bool found = false;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		if (d->records[i].kind == KR_CONFIRMED && !memcmp(d->records[i].id, id, 16)) {
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
	uint8_t request[KR_FRAME_MAX];
	uint8_t reply[KR_FRAME_MAX];
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

static void test_three_devices(void)
{
	unsigned cursors[3] = {0};
	for (unsigned i = 0; i < 3; ++i) {
		setup(i);
	}
	for (unsigned round = 0; round < 80; ++round) {
		for (unsigned source = 0; source < 3; ++source) {
			kr_drain(&engines[source]);
			int sent;
			size_t len = kr_advertisement(&engines[source], &cursors[source], packet, &sent);
			for (unsigned target = 0; target < 3; ++target) {
				if (source != target) {
					(void)kr_receive(&engines[target], packet, len, source + 1, 1000 + source);
				}
			}
			kr_sent(&engines[source], sent);
		}
	}
	for (unsigned i = 0; i < 3; ++i) {
		assert(kr_count(&engines[i], KR_CONFIRMED) == 2);
		assert(disks[i].feedback == 2);
	}
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
	uint8_t frame[KR_FRAME_MAX];
	size_t len = 0;
	unsigned value;
	while (fscanf(file, "%2x", &value) == 1) {
		assert(len < sizeof(frame));
		frame[len++] = (uint8_t)value;
	}
	fclose(file);
	setup(0);
	uint8_t response[KR_FRAME_MAX];
	uint64_t utc;
	assert(kr_phone_process(&engines[0], frame, len, response, &utc) > 0);
	assert(response[10] == KR_OK);
	assert(utc == 1700000000);
	assert(engines[0].config.profile.revision == 7);
	assert(engines[0].config.profile.data_len == 1024);
	for (unsigned i = 0; i < 1024; ++i) {
		assert(engines[0].config.profile.data[i] == (uint8_t)i);
	}
	for (size_t i = 0; i < len; ++i) {
		frame[i] ^= 1;
		assert(kr_phone_process(&engines[0], frame, len, response, &utc) == 0);
		frame[i] ^= 1;
	}
}

int main(int argc, char **argv)
{
	test_codec();
	test_registration_and_sync();
	test_shared_event_loss_restart_and_receipt();
	test_immediate_fifo_and_failure_queues();
	test_storage_failure();
	test_phone_frames();
	test_every_handshake_restart();
	test_three_devices();
	test_upload_before_final_radio_receipt();
	test_review_backpressure_and_corrupt_restore();
	if (argc > 1) {
		test_sync_fixture(argv[1]);
	}
	printf("PASS: binary bounds/CRC, multi-device registration, durable sync, bilateral event, "
		"12 restart cuts, 3 peers, immediate FIFO, three queues, failure evidence, "
		"backpressure, corrupt storage, durable receipts, shared wire fixture\n");
	return 0;
}

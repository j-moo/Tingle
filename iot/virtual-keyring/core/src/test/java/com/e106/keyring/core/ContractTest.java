package com.e106.keyring.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import com.e106.keyring.core.SavedEncounter.Phase;

/** End-to-end device contract scenarios, using serialized packets and durable-state restarts. */
public final class ContractTest {
	private static int passed;

	private ContractTest() {
	}

	public static void main(String[] args) {
		run("complete contract flow, bilateral results, ordered stages and own evidence", ContractTest::normal);
		run("every lost message/ACK recovers without inventing a new encounter", ContractTest::loss);
		run("process restarts at every message retain records and one feedback claim", ContractTest::restart);
		run("duplicate packets and OFF/restart preserve suppression", ContractTest::duplicates);
		run("failed durable writes never send successful storage ACKs", ContractTest::storageFailures);
		run("all four notification settings, manual stop and restart stay silent", ContractTest::notifications);
		run("self, OFF, photo, version and relation gates override the personality stub", ContractTest::gates);
		run("condition-completion queue is FIFO and releases departed candidates", ContractTest::queue);
		run("expired encounters are neither extended nor replaced", ContractTest::expiry);
		run("binary profile/event roundtrip and foreign message validation", ContractTest::wire);
		run("always-match stub accepts three contrasting synthetic input pairs", ContractTest::alwaysMatch);
		System.out.println("PASS: " + passed + " contract scenario groups");
	}

	private static void run(String label, Runnable test) {
		test.run();
		passed++;
		System.out.println("PASS " + label);
	}

	private static void normal() {
		Harness harness = new Harness();
		harness.start();
		harness.finish();
		harness.confirmed();
		Encounter event = harness.left.list().get(0).encounter;
		long retries = count(harness.rightLogs, "TURN_RETRY");
		harness.second.receive(Packet.recheck(Packet.RECHECK_BUSY, event.recheckToken,
			DemoProfile.context(event.profileA, event.profileB)));
		check(count(harness.rightLogs, "TURN_RETRY") == retries,
			"late BUSY cannot reopen a completed confirmation queue reservation");
		check(event.profileA.userId.toString().compareTo(event.profileB.userId.toString()) < 0, "A/B by user ID");
		check(!event.deviceA.equals(harness.leftProfile.keyring.deviceId), "user order is independent of device order");
		check(event.expiresAt == event.occurredAt + DemoProfile.OPPORTUNITY_MS, "policy deadline");
		SavedEncounter left = harness.left.list().get(0);
		SavedEncounter right = harness.right.list().get(0);
		check(!left.submissionId.equals(right.submissionId), "device submissions are distinct");
		check(!left.evidence.payload.equals(right.evidence.payload), "device proofs are distinct");
		check(left.evidence.snapshotId.equals(harness.leftProfile.keyring.snapshotId), "own original snapshot");
		for (List<FlowEvent> logs : List.of(harness.leftLogs, harness.rightLogs)) {
			ordered(logs, "AUTHENTICATED", "MATCH_DATA_RECEIVED", "ALGORITHM_QUEUE", "MATCH_EVALUATING",
				"MATCH_EVALUATED", "BOTH_CONDITIONS_PASSED", "CONFIRMATION_QUEUE", "RECHECK_PASSED",
				"PREPARED_LOCALLY", "CONFIRMED_LOCALLY", "UPLOAD_PENDING", "BOTH_STORED", "FEEDBACK_READY");
		}
	}

	private static void loss() {
		for (int type = Packet.MATCH_DATA; type <= Packet.RECEIPT; type++) {
			Harness harness = new Harness();
			harness.dropType = type;
			harness.start();
			harness.finish();
			harness.confirmed();
			check(harness.dropped, "exercised loss type=" + type);
		}
	}

	private static void restart() {
		for (int type = Packet.MATCH_DATA; type <= Packet.RECEIPT; type++) {
			Harness harness = new Harness();
			harness.dropType = type;
			harness.start();
			for (int step = 0; step < 12 && !harness.dropped; step++) {
				harness.step();
			}
			List<SavedEncounter> before = new ArrayList<>(harness.left.list());
			harness.restart();
			harness.finish();
			harness.confirmed();
			for (SavedEncounter old : before) {
				SavedEncounter retained = harness.left.find(old.encounter.eventId);
				check(retained != null && retained.encounter.sameAs(old.encounter)
					&& retained.submissionId.equals(old.submissionId)
					&& retained.evidence.payload.equals(old.evidence.payload), "restart retains evidence type=" + type);
			}
		}
	}

	private static void duplicates() {
		Harness harness = new Harness();
		harness.duplicate = true;
		harness.start();
		harness.finish();
		UUID original = harness.left.list().get(0).encounter.eventId;
		for (int attempt = 0; attempt < 4; attempt++) {
			harness.restart();
			harness.finish();
		}
		harness.confirmed();
		check(harness.left.list().get(0).encounter.eventId.equals(original), "OFF/restart retains original event");
		check(count(harness.leftLogs, "FEEDBACK_READY") == 1 && count(harness.rightLogs, "FEEDBACK_READY") == 1,
			"one claim across restarts");
	}

	private static void storageFailures() {
		for (Phase phase : Phase.values()) {
			for (boolean leftSide : new boolean[] {true, false}) {
				Harness harness = new Harness();
				Store failing = leftSide ? harness.left : harness.right;
				if (phase == Phase.COMMITTED && leftSide) {
					continue; // right user is the coordinator in this fixture.
				}
				failing.fail = phase;
				harness.start();
				harness.finish();
				harness.confirmed();
				check(failing.failed, "write failure exercised: " + phase + "/" + leftSide);
			}
		}
	}

	private static void notifications() {
		for (boolean first : new boolean[] {true, false}) {
			for (boolean second : new boolean[] {true, false}) {
				Harness harness = new Harness();
				harness.leftProfile = options(harness.leftProfile, true, first, true, false);
				harness.rightProfile = options(harness.rightProfile, true, second, true, false);
				harness.start();
				harness.finish();
				harness.confirmed();
				int expected = first && second ? 1 : 0;
				check(count(harness.leftLogs, "FEEDBACK_READY") == expected
					&& count(harness.rightLogs, "FEEDBACK_READY") == expected, "both settings govern both devices");
				UUID event = harness.left.list().get(0).encounter.eventId;
				harness.left.finishFeedback(event, "STOPPED");
				harness.restart();
				harness.finish();
				check(count(harness.leftLogs, "FEEDBACK_READY") == expected, "stopped alert never replays");
			}
		}
	}

	private static void gates() {
		for (int variant = 0; variant < 5; variant++) {
			Harness harness = new Harness();
			if (variant == 0) {
				harness.leftProfile = new DemoProfile(harness.rightProfile.userId, harness.leftProfile.keyring,
					true, true, true, false, 25, new byte[5], new byte[5]);
			} else if (variant == 1) {
				harness.leftProfile = options(harness.leftProfile, false, true, true, false);
			} else if (variant == 2) {
				harness.leftProfile = options(harness.leftProfile, true, true, true, true);
				harness.rightProfile = options(harness.rightProfile, true, true, false, false);
			} else if (variant == 3) {
				DemoProfile old = harness.leftProfile;
				harness.leftProfile = new DemoProfile(old.userId, old.keyring, true, true, true, false, 25,
					new byte[5], new byte[5], "lab-other-policy", DemoProfile.RULE, DemoProfile.OPPORTUNITY_MS);
			} else {
				harness.blocked = true;
			}
			harness.start();
			for (int step = 0; step < 4; step++) {
				harness.step();
			}
			check(harness.left.list().isEmpty() && harness.right.list().isEmpty(), "ineligible gate=" + variant);
		}
	}

	private static void queue() {
		AlgorithmQueue algorithms = new AlgorithmQueue();
		List<Integer> calculated = new ArrayList<>();
		UUID job = UUID.randomUUID();
		algorithms.submit(job, () -> calculated.add(1));
		algorithms.submit(UUID.randomUUID(), () -> calculated.add(2));
		check(calculated.isEmpty() && algorithms.size() == 2, "received input actually waits before calculation");
		algorithms.runNext();
		check(calculated.equals(List.of(1)) && algorithms.size() == 1, "one received input calculated at a time");
		algorithms.runNext();
		check(calculated.equals(List.of(1, 2)), "algorithm queue preserves input arrival order");
		CandidateQueue queue = new CandidateQueue();
		UUID early = new UUID(0, 9);
		UUID later = new UUID(0, 1);
		check(queue.add(early) && queue.add(later) && !queue.add(early), "first completion wins, duplicates collapse");
		check(!queue.acquire(later) && queue.acquire(early), "FIFO rather than identifier/score order");
		check(!queue.acquire(later), "one active candidate");
		queue.remove(early);
		check(queue.acquire(later), "next candidate proceeds when earlier leaves");
		Harness harness = new Harness();
		harness.start();
		harness.drain();
		harness.elapsed += ContractEngine.PRESENCE_MS + 1;
		harness.first.tick();
		harness.second.tick();
		check(harness.left.list().isEmpty() && harness.right.list().isEmpty(), "departure before turn prevents event");
		check(count(harness.leftLogs, "PEER_LEFT") == 1, "departure explained in log");
	}

	private static void expiry() {
		Harness harness = new Harness();
		harness.start();
		harness.finish();
		Encounter original = harness.left.list().get(0).encounter;
		harness.wall = original.expiresAt + 1;
		harness.restart();
		for (int step = 0; step < 3; step++) {
			harness.step();
		}
		check(harness.left.list().size() == 1 && harness.left.find(original.eventId).encounter.sameAs(original),
			"deadline never renewed");
		check(count(harness.leftLogs, "FEEDBACK_READY") == 1, "no expired feedback");
	}

	private static void wire() {
		Harness harness = new Harness();
		DemoProfile profile = harness.leftProfile;
		Packet decoded = Packet.decode(Packet.profile(profile).encode());
		check(profile.sameAs(decoded.profile), "profile binary roundtrip");
		byte[] malformed = Packet.profile(profile).encode();
		malformed[59] = (byte)255;
		boolean rejected = false;
		try {
			Packet.decode(malformed);
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}
		check(rejected, "invalid profile flags rejected");
		harness.start();
		harness.finish();
		Encounter event = harness.left.list().get(0).encounter;
		byte[] encoded = Packet.event(Packet.PREPARE, event).encode();
		check(encoded.length <= Packet.MAX_BYTES && Packet.decode(encoded).encounter.sameAs(event), "event roundtrip");
		AdvertisementCodec codec = new AdvertisementCodec("00112233445566778899AABBCCDDEEFF");
		byte[] sealed = codec.seal(event.deviceA, 7, event.deviceB, 8,
			Packet.event(Packet.PREPARE, event), harness.wall);
		check(codec.open(sealed, harness.wall).packet.encounter.sameAs(event),
			"full event fits authenticated envelope");
		byte[] frozen = harness.left.list().get(0).evidence.payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		harness.restart();
		harness.finish();
		check(Arrays.equals(frozen, harness.left.list().get(0).evidence.payload
			.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "proof is retained, never regenerated");
	}

	private static void alwaysMatch() {
		Harness harness = new Harness();
		for (byte value : new byte[] {0, 50, 100}) {
			byte[] vector = new byte[] {value, value, value, value, value};
			DemoProfile input = new DemoProfile(UUID.randomUUID(), harness.leftProfile.keyring,
				true, true, true, false, 25, vector, new byte[5]);
			check(MatchingRule.alwaysMatch().matches(input, harness.rightProfile),
				"stub always accepts vector=" + value);
		}
	}

	private static DemoProfile options(DemoProfile original, boolean matching, boolean notification,
		boolean photo, boolean requirePhoto) {
		return new DemoProfile(original.userId, original.keyring, matching, notification, photo, requirePhoto,
			original.age, original.personality(), original.desired());
	}

	private static long count(List<FlowEvent> events, String code) {
		return events.stream().filter(value -> value.code.equals(code)).count();
	}

	private static void ordered(List<FlowEvent> events, String... codes) {
		int position = 0;
		for (FlowEvent event : events) {
			if (event.code.equals(codes[position]) && ++position == codes.length) {
				return;
			}
		}
		throw new AssertionError("Missing ordered stage " + codes[position] + ": "
			+ events.stream().map(value -> value.code).toList());
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static final class Harness {
		private DemoProfile leftProfile = new DemoProfile(new UUID(0, 20),
			new Peer(new UUID(0, 1), UUID.randomUUID(), 1, true), true, true, true, false, 25,
			new byte[] {10, 20, 30, 40, 50}, new byte[] {90, 80, 70, 60, 50});
		private DemoProfile rightProfile = new DemoProfile(new UUID(0, 10),
			new Peer(new UUID(0, 2), UUID.randomUUID(), 1, true), true, true, true, false, 26,
			new byte[] {90, 80, 70, 60, 50}, new byte[] {10, 20, 30, 40, 50});
		private final Store left = new Store(leftProfile.keyring.deviceId);
		private final Store right = new Store(rightProfile.keyring.deviceId);
		private final Queue<Delivery> network = new ArrayDeque<>();
		private final List<FlowEvent> leftLogs = new ArrayList<>();
		private final List<FlowEvent> rightLogs = new ArrayList<>();
		private ContractEngine first;
		private ContractEngine second;
		private long elapsed = 1000;
		private long wall = 1800000000000L;
		private int dropType = -1;
		private boolean dropped;
		private boolean duplicate;
		private boolean blocked;

		private void start() {
			first = new ContractEngine(leftProfile, rightProfile.keyring.deviceId, left, new CandidateQueue(),
				MatchingRule.alwaysMatch(), packet -> network.add(new Delivery(false, packet)), leftLogs::add,
				() -> wall, () -> elapsed, user -> !blocked);
			second = new ContractEngine(rightProfile, leftProfile.keyring.deviceId, right, new CandidateQueue(),
				MatchingRule.alwaysMatch(), packet -> network.add(new Delivery(true, packet)), rightLogs::add,
				() -> wall, () -> elapsed);
			first.start();
			second.start();
		}

		private void restart() {
			first.close();
			second.close();
			network.clear();
			start();
		}

		private void drain() {
			int count = 0;
			while (!network.isEmpty()) {
				check(++count < 1000, "no unbounded packet ping-pong");
				Delivery item = network.remove();
				int logicalType = item.packet.type == Packet.PREPARE_REFERENCE ? Packet.PREPARE : item.packet.type;
				if (logicalType == dropType && !dropped) {
					dropped = true;
					continue;
				}
				ContractEngine target = item.left ? first : second;
				try {
					target.receive(Packet.decode(item.packet.encode()));
					if (duplicate) {
						target.receive(Packet.decode(item.packet.encode()));
					}
				} catch (IllegalStateException expected) {
					check("INJECTED_WRITE_FAILURE".equals(expected.getMessage()), "unexpected error " + expected);
				}
			}
		}

		private void step() {
			drain();
			elapsed += 30000;
			wall += 30000;
			first.receive(Packet.hello(rightProfile.keyring));
			second.receive(Packet.hello(leftProfile.keyring));
			try {
				first.tick();
				second.tick();
			} catch (IllegalStateException expected) {
				check("INJECTED_WRITE_FAILURE".equals(expected.getMessage()), "unexpected tick " + expected);
			}
			drain();
		}

		private void finish() {
			for (int step = 0; step < 30; step++) {
				step();
				if (left.list().size() == 1 && right.list().size() == 1
					&& left.list().get(0).phase == Phase.CONFIRMED && right.list().get(0).phase == Phase.CONFIRMED
					&& !left.feedbackState(left.list().get(0).encounter.eventId).isEmpty()
					&& !right.feedbackState(right.list().get(0).encounter.eventId).isEmpty()) {
					return;
				}
			}
			throw new AssertionError("flow did not finish; drop=" + dropType + " left=" + first.stage()
				+ " right=" + second.stage() + " records=" + left.records.size() + "/" + right.records.size());
		}

		private void confirmed() {
			check(left.list().size() == 1 && right.list().size() == 1, "one encounter on both sides");
			check(left.list().get(0).phase == Phase.CONFIRMED && right.list().get(0).phase == Phase.CONFIRMED,
				"confirmed on both sides");
			check(left.list().get(0).encounter.sameAs(right.list().get(0).encounter), "identical shared event");
		}
	}

	private static final class Delivery {
		private final boolean left;
		private final Packet packet;

		private Delivery(boolean left, Packet packet) {
			this.left = left;
			this.packet = packet.type == Packet.PREPARE ? Packet.preparation(packet.encounter) : packet;
		}
	}

	private static final class Store implements ContractStore {
		private final UUID deviceId;
		private final Map<UUID, SavedEncounter> records = new LinkedHashMap<>();
		private final Map<UUID, String> feedback = new HashMap<>();
		private Phase fail;
		private boolean failed;

		private Store(UUID deviceId) {
			this.deviceId = deviceId;
		}

		@Override
		public SavedEncounter find(UUID eventId) {
			return records.get(eventId);
		}

		@Override
		public SavedEncounter findPair(UUID first, UUID second) {
			return records.values().stream().filter(value -> value.encounter.peerOf(first).equals(second))
				.findFirst().orElse(null);
		}

		@Override
		public SavedEncounter save(Encounter event, Phase phase) {
			if (phase == fail && !failed) {
				failed = true;
				throw new IllegalStateException("INJECTED_WRITE_FAILURE");
			}
			SavedEncounter old = find(event.eventId);
			check(old == null || old.encounter.sameAs(event), "payload immutable");
			UUID submission = old == null ? UUID.randomUUID() : old.submissionId;
			SavedEncounter saved = new SavedEncounter(event, submission,
				old == null ? phase : SavedEncounter.advance(old.phase, phase),
				old == null ? LocalEvidence.create(event, deviceId, submission) : old.evidence);
			records.put(event.eventId, saved);
			return saved;
		}

		@Override
		public List<SavedEncounter> list() {
			return new ArrayList<>(records.values());
		}

		@Override
		public boolean claimFeedback(UUID eventId, String state) {
			return feedback.putIfAbsent(eventId, state) == null;
		}

		@Override
		public void finishFeedback(UUID eventId, String state) {
			feedback.put(eventId, state);
		}

		@Override
		public String feedbackState(UUID eventId) {
			return feedback.getOrDefault(eventId, "");
		}
	}
}

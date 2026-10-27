package com.e106.keyring.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Executable regression tests for the profile-only production pipeline. */
public final class IndependentTest {
	private IndependentTest() {
	}

	public static void main(String[] args) {
		immediateAndFailures();
		storageFailure();
		fifoAndExpiry();
		gates();
		wireValidation();
		for (int seed : new int[] {7, 41, 106}) {
			for (int count : new int[] {2, 4, 10}) {
				fleet(count, 0, false, seed);
				fleet(count, 0.35, false, seed);
			}
			fleet(4, 0.35, true, seed);
		}
		System.out.println("PASS independent processing: immediate FIFO, isolated durable retries, restart, "
			+ "idempotent storage, local-only feedback, gates, wire validation and 21 fleet scenarios");
	}

	private static DemoProfile profile() {
		return DemoProfile.fixture(new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true));
	}

	private static void immediateAndFailures() {
		long[] now = {1000};
		DemoProfile local = profile();
		DemoProfile bad = profile();
		DemoProfile good = profile();
		Store store = new Store(local.keyring.deviceId);
		int[] calls = {0};
		boolean[] fail = {true};
		List<FlowEvent> events = new ArrayList<>();
		IndependentMatcher matcher = new IndependentMatcher(local, store, (first, second) -> {
			calls[0]++;
			if (fail[0] && second.userId.equals(bad.userId)) {
				throw new IllegalArgumentException("INJECTED_ALGORITHM_FAILURE");
			}
			return true;
		}, () -> now[0], events::add);
		matcher.receive(bad);
		UUID eventId = matcher.failures().get(0).eventId;
		check(matcher.errorCount() == 1 && matcher.nextRetryAt() == 2000, "failed job alone has timer");
		matcher.receive(good);
		check(store.list().size() == 1 && store.list().get(0).encounter.occurredAt == 1000,
			"next candidate saves immediately at same instant despite first failure");
		check(events.stream().anyMatch(value -> value.code.equals("LOCAL_SAVED")
			&& value.detail.contains("receive_to_save_ms=0")), "no artificial queue wait");
		int attempts = calls[0];
		matcher.receive(bad);
		now[0] = 1999;
		matcher.retryDue();
		check(calls[0] == attempts, "duplicate input does not reset or bypass failed retry deadline");
		now[0] = 2000;
		matcher.retryDue();
		check(matcher.nextRetryAt() == 4000, "failed-only exponential retry");
		check(store.pendingWork().get(0).attempts == 2, "failed attempt durable");
		fail[0] = false;
		IndependentMatcher reopened = new IndependentMatcher(local, store, MatchingRule.alwaysMatch(),
			() -> now[0], events::add);
		reopened.restore();
		check(store.list().size() == 1 && reopened.errorCount() == 1, "restart preserves due time");
		now[0] = 4000;
		reopened.retryDue();
		check(store.find(eventId) != null && store.pendingWork().isEmpty(), "same job and event recovered");
		check(reopened.nextRetryAt() == 0, "success removes timer");
		long feedback = events.stream().filter(value -> value.code.equals("FEEDBACK_READY")).count();
		reopened.receive(good);
		reopened.receive(bad);
		check(store.list().size() == 2 && feedback == 2, "two successful inputs, two local alerts");
		check(events.stream().filter(value -> value.code.equals("FEEDBACK_READY")).count() == feedback,
			"repeat reception and restart cannot repeat feedback");
	}

	private static void storageFailure() {
		long[] now = {1000};
		DemoProfile local = profile();
		Store store = new Store(local.keyring.deviceId);
		store.failAfterSave = true;
		IndependentMatcher matcher = new IndependentMatcher(local, store, MatchingRule.alwaysMatch(),
			() -> now[0], event -> { });
		matcher.receive(profile());
		check(store.list().size() == 1 && matcher.errorCount() == 1, "ambiguous save stays failed");
		SavedEncounter original = store.list().get(0);
		now[0] = 2000;
		matcher.retryDue();
		check(store.list().size() == 1
			&& store.find(original.encounter.eventId).submissionId.equals(original.submissionId),
			"save retry is idempotent after transaction committed before exception");
		check(store.pendingWork().isEmpty() && !store.feedbackState(original.encounter.eventId).isEmpty(),
			"storage error resolved, feedback claimed once");
		Packet decoded = Packet.decode(Packet.event(Packet.LOCAL_RECORD, original.encounter).encode());
		check(decoded.encounter.sameAs(original.encounter) && !decoded.encounter.isContract(),
			"local event roundtrip cannot masquerade as shared contract record");
	}

	private static void gates() {
		for (int variant = 0; variant < 4; variant++) {
			DemoProfile local = profile();
			DemoProfile fixture = profile();
			DemoProfile remote = new DemoProfile(fixture.userId, fixture.keyring, variant != 0, variant != 2,
				true, false, 25, fixture.personality(), fixture.desired(),
				variant == 1 ? "unknown-policy" : DemoProfile.POLICY, DemoProfile.RULE, DemoProfile.OPPORTUNITY_MS);
			Store store = new Store(local.keyring.deviceId);
			List<FlowEvent> events = new ArrayList<>();
			IndependentMatcher matcher = new IndependentMatcher(local, store, MatchingRule.alwaysMatch(),
				() -> 1000, events::add);
			matcher.receive(local);
			matcher.receive(remote);
			check(store.list().size() == (variant >= 2 ? 1 : 0), "self, OFF and incompatible policy gates");
			long alerts = events.stream().filter(value -> value.code.equals("FEEDBACK_READY")).count();
			check(alerts == (variant == 3 ? 1 : 0),
				"both settings must allow feedback");
			check(matcher.errorCount() == 0 && matcher.nextRetryAt() == 0,
				"negative decision is not a processing error");
		}
		DemoProfile base = profile();
		DemoProfile filtered = new DemoProfile(base.userId, base.keyring, true, true, true, true, 25,
			base.personality(), base.desired());
		DemoProfile other = profile();
		DemoProfile noPhoto = new DemoProfile(other.userId, other.keyring, true, true, false, false, 25,
			other.personality(), other.desired());
		Store store = new Store(filtered.keyring.deviceId);
		IndependentMatcher matcher = new IndependentMatcher(filtered, store, MatchingRule.alwaysMatch(),
			() -> 1000, event -> { });
		matcher.receive(noPhoto);
		check(store.list().isEmpty() && matcher.errorCount() == 0, "photo gate rejects without retry timer");
	}

	private static void fifoAndExpiry() {
		long[] now = {1000};
		DemoProfile local = profile();
		DemoProfile first = profile();
		DemoProfile second = profile();
		DemoProfile third = profile();
		List<UUID> order = new ArrayList<>();
		Store store = new Store(local.keyring.deviceId);
		IndependentMatcher[] holder = new IndependentMatcher[1];
		holder[0] = new IndependentMatcher(local, store, (own, remote) -> {
			if (remote.userId.equals(first.userId)) {
				throw new IllegalStateException("RULE_FAILED");
			}
			return true;
		}, () -> now[0], event -> {
			if (event.code.equals("LOCAL_EVALUATING")) {
				order.add(event.peer);
				if (event.peer.equals(first.keyring.deviceId)) {
					holder[0].receive(second);
					holder[0].receive(third);
				}
			}
		});
		holder[0].receive(first);
		check(order.equals(List.of(first.keyring.deviceId, second.keyring.deviceId, third.keyring.deviceId)),
			"work arriving during processing drains in FIFO order after a failure");
		check(store.list().size() == 2 && now[0] == 1000, "queue emptying has no pacing delay");
		now[0] += local.opportunityMillis;
		holder[0].retryDue();
		check(holder[0].errorCount() == 0 && store.list().size() == 2,
			"old failed input cannot extend its opportunity");
	}

	private static void wireValidation() {
		DemoProfile local = profile();
		AdvertisementCodec codec = new AdvertisementCodec("1234567890ABCDEF1234567890ABCDEF");
		byte[] good = codec.seal(local.keyring.deviceId, 1, AdvertisementCodec.BROADCAST, 0,
			Packet.broadcastProfile(local), 1000);
		check(codec.open(good, 1000).packet.profile.sameAs(local), "full profile with settings and traits roundtrip");
		check(ExtendedAdvertisement.wrap(good, 251).length + 18 == 216, "full advertisement is 216 bytes");
		byte[] corrupt = good.clone();
		corrupt[corrupt.length - 1] ^= 1;
		expectRejected(() -> codec.open(corrupt, 1000));
		expectRejected(() -> codec.open(java.util.Arrays.copyOf(good, good.length - 5), 1000));
		byte[] wrongSender = codec.seal(UUID.randomUUID(), 1, AdvertisementCodec.BROADCAST, 0,
			Packet.broadcastProfile(local), 1000);
		expectRejected(() -> codec.open(wrongSender, 1000));
	}

	private static void expectRejected(Runnable operation) {
		try {
			operation.run();
			throw new AssertionError("invalid message accepted");
		} catch (IllegalArgumentException expected) {
			// Unauthenticated/incomplete payloads cannot enter the algorithm queue.
		}
	}

	private static void fleet(int count, double loss, boolean legacy, int seed) {
		AdvertisementCodec codec = new AdvertisementCodec("1234567890ABCDEF1234567890ABCDEF");
		List<Store> stores = new ArrayList<>();
		List<ProfileBroadcaster> senders = new ArrayList<>();
		List<IndependentMatcher> receivers = new ArrayList<>();
		List<AdvertisementFrames> fragments = new ArrayList<>();
		long[] now = {1000};
		int[] feedback = {0};
		for (int index = 0; index < count; index++) {
			DemoProfile own = profile();
			Store store = new Store(own.keyring.deviceId);
			stores.add(store);
			ProfileBroadcaster sender = new ProfileBroadcaster(codec, own, index + 1);
			sender.configureExtended(true, 251);
			senders.add(sender);
			fragments.add(new AdvertisementFrames());
			receivers.add(new IndependentMatcher(own, store, MatchingRule.alwaysMatch(), () -> now[0], event -> {
				if (event.code.equals("FEEDBACK_READY")) {
					feedback[0]++;
				}
			}));
		}
		Random random = new Random(seed);
		long completedAt = 0;
		for (int step = 0; step < 1200; step++) {
			now[0] = 1000 + step * 300L;
			for (int sender = 0; sender < count; sender++) {
				byte[] full = senders.get(sender).nextFrame(true, now[0], now[0]);
				byte[] part = senders.get(sender).nextFrame(false, now[0], now[0]);
				for (int receiver = 0; receiver < count; receiver++) {
					if (sender == receiver || random.nextDouble() < loss) {
						continue;
					}
					boolean useLegacy = legacy && (sender == 0 || receiver == 0);
					byte[] bytes = useLegacy ? fragments.get(receiver).accept("sender-" + sender, part, now[0])
						: ExtendedAdvertisement.unwrap(full);
					if (bytes != null) {
						Packet packet = codec.open(bytes, now[0]).packet;
						check(packet.type == Packet.PROFILE_BROADCAST, "only own profile travels over radio");
						receivers.get(receiver).receive(packet.profile);
					}
				}
			}
			if (stores.stream().allMatch(store -> store.list().size() == count - 1)) {
				completedAt = now[0] - 1000;
				break;
			}
		}
		check(stores.stream().allMatch(store -> store.list().size() == count - 1), "every receiver saves all peers");
		check(receivers.stream().allMatch(matcher -> matcher.errorCount() == 0 && matcher.nextRetryAt() == 0),
			"no work or retry timer left after normal processing");
		check(feedback[0] == count * (count - 1), "one local alert per peer");
		System.out.println("FLEET devices=" + count + " loss=" + loss + " mixed=" + legacy + " seed=" + seed
			+ " radio_to_all_saved_ms=" + completedAt + " records=" + feedback[0] + " intentional_match_wait_ms=0");
	}

	private static void check(boolean valid, String message) {
		if (!valid) {
			throw new AssertionError(message);
		}
	}

	private static final class Store implements LocalMatchStore {
		private final UUID device;
		private final Map<UUID, SavedEncounter> records = new LinkedHashMap<>();
		private final Map<UUID, byte[]> work = new LinkedHashMap<>();
		private final Map<UUID, String> feedback = new LinkedHashMap<>();
		private boolean failAfterSave;

		private Store(UUID device) {
			this.device = device;
		}

		@Override
		public SavedEncounter find(UUID id) {
			return records.get(id);
		}

		@Override
		public SavedEncounter findPair(UUID first, UUID second) {
			return records.values().stream().filter(saved -> saved.encounter.deviceA.equals(first)
				&& saved.encounter.deviceB.equals(second)).findFirst().orElse(null);
		}

		@Override
		public SavedEncounter save(Encounter event, SavedEncounter.Phase phase) {
			SavedEncounter previous = records.get(event.eventId);
			if (previous != null) {
				check(previous.encounter.sameAs(event), "same retry input");
				return previous;
			}
			UUID submission = UUID.randomUUID();
			SavedEncounter saved = new SavedEncounter(event, submission, phase,
				LocalEvidence.create(event, device, submission));
			records.put(event.eventId, saved);
			if (failAfterSave) {
				failAfterSave = false;
				throw new IllegalStateException("COMMITTED_BEFORE_EXCEPTION");
			}
			return saved;
		}

		@Override
		public List<SavedEncounter> list() {
			return new ArrayList<>(records.values());
		}

		@Override
		public boolean claimFeedback(UUID id, String state) {
			return feedback.putIfAbsent(id, state) == null;
		}

		@Override
		public void finishFeedback(UUID id, String state) {
			feedback.put(id, state);
		}

		@Override
		public String feedbackState(UUID id) {
			return feedback.getOrDefault(id, "");
		}

		@Override
		public List<MatchWork> pendingWork() {
			return work.values().stream().map(MatchWork::decode).toList();
		}

		@Override
		public void putWork(MatchWork job) {
			work.put(job.id, job.encode());
		}

		@Override
		public void removeWork(UUID id) {
			work.remove(id);
		}
	}
}

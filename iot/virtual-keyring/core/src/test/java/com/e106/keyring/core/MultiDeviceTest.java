package com.e106.keyring.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.e106.keyring.core.SavedEncounter.Phase;

/** Production scheduler/codec/engine with simulated loss, delivery order, clocks and durable stores. */
public final class MultiDeviceTest {
	private static final String GROUP = "00112233445566778899AABBCCDDEEFF";
	private static final long WALL = 1792742400000L;
	private static long now;
	private static long randomSeed = 106;

	private MultiDeviceTest() {
	}

	public static void main(String[] args) {
		if (args.length > 0) {
			randomSeed = Long.parseLong(args[0]);
		}
		wire();
		routing();
		for (int count : new int[] {2, 4, 10}) {
			for (double loss : new double[] {0, 0.35}) {
				run(count, 0, loss, 600);
			}
		}
		run(4, 1, 0, 600);
		run(4, 1, 0.2, 600);
		run(4, 4, 0, 300);
		System.out.println("PASS: extended wire boundaries and 9 multi-device radio scenarios");
	}

	private static void routing() {
		Node local = new Node(0, 2, true);
		Node remote = new Node(1, 2, true);
		RadioOutbox pending = new RadioOutbox(local.codec, local.profile.keyring, local.session);
		pending.configureExtended(true, 255);
		pending.remember(remote.id, remote.session, true);
		check(!pending.usesExtended(remote.id, remote.session), "API support does not imply actual start success");
		pending.setExtendedReady();
		check(pending.usesExtended(remote.id, remote.session), "both peers negotiated extended support");
		check(!pending.usesExtended(remote.id, remote.session + 1), "capability belongs to the authenticated session");
		pending.send(remote.id, remote.session, Packet.profile(local.profile), 1);
		byte[] frame = null;
		for (int slot = 0; slot < 4; slot++) {
			byte[] candidate = pending.nextFrame(true, 1 + slot * 300L, WALL + slot * 300L);
			if (pending.currentMessage(true).packet.type == Packet.MATCH_DATA) {
				frame = candidate;
			}
		}
		check(frame != null && frame.length + 18 == 216, "one complete profile within the 255-byte budget");
		pending.configureExtended(false, 255);
		check(!pending.usesExtended(remote.id, remote.session), "controller failure switches to legacy");
		AdvertisementFrames fragments = new AdvertisementFrames();
		boolean recovered = false;
		for (int slot = 0; slot < 200; slot++) {
			long tick = 2000 + slot * 300L;
			byte[] fragment = pending.nextFrame(false, tick, WALL + tick);
			check(fragment.length + 18 <= 31, "fallback fits legacy advertisement");
			byte[] encrypted = fragments.accept(local.id.toString(), fragment, tick);
			if (encrypted != null) {
				Packet packet = local.codec.open(encrypted, WALL + tick).packet;
				if (packet.type == Packet.MATCH_DATA) {
					check(packet.profile.sameAs(local.profile), "fallback retains the original queued payload");
					recovered = true;
					break;
				}
			}
		}
		check(recovered, "fallback delivers a pending complete profile");
		pending.forgetPeer(remote.id);
		check(pending.size() == 0, "peer session reset removes obsolete addressed messages");
	}

	private static void wire() {
		check(!ExtendedAdvertisement.supported(false, 1650), "unsupported adapter");
		check(!ExtendedAdvertisement.supported(true, 215), "insufficient controller capacity");
		check(ExtendedAdvertisement.supported(true, 216), "minimum full profile capacity");
		byte[] limit = new byte[ExtendedAdvertisement.MAX_ENCRYPTED_BYTES];
		check(ExtendedAdvertisement.wrap(limit, 255).length + 18 == 251, "enabled update ceiling");
		expectFailure(() -> ExtendedAdvertisement.wrap(new byte[limit.length + 1], 255));
		expectFailure(() -> ExtendedAdvertisement.unwrap(new byte[] {0x40}));
		Node first = new Node(0, 2, true);
		Node second = new Node(1, 2, true);
		DemoProfile firstProfile = first.profile.userId.compareTo(second.profile.userId) < 0
			? first.profile : second.profile;
		DemoProfile secondProfile = firstProfile == first.profile ? second.profile : first.profile;
		Encounter event = new Encounter(UUID.randomUUID(), firstProfile, secondProfile, WALL, UUID.randomUUID());
		List<Packet> packets = List.of(Packet.hello(firstProfile.keyring, true), Packet.profile(firstProfile),
			Packet.decision(DemoProfile.context(firstProfile, secondProfile), true),
			Packet.recheck(Packet.RECHECK, event.recheckToken, DemoProfile.context(firstProfile, secondProfile)),
			Packet.preparation(event), Packet.ping(Packet.RECEIPT, event.eventId), Packet.error("STORAGE_ERROR"));
		for (Packet packet : packets) {
			byte[] encoded = first.codec.seal(firstProfile.keyring.deviceId, 1,
				secondProfile.keyring.deviceId, 2, packet, WALL);
			byte[] frame = ExtendedAdvertisement.wrap(encoded, 255);
			Packet restored = first.codec.open(ExtendedAdvertisement.unwrap(frame), WALL).packet;
			check(Arrays.equals(packet.encode(), restored.encode()), "whole-message authenticated round trip");
			System.out.println("WIRE type=" + packet.type + " ad_bytes=" + (frame.length + 18) + " pieces=1");
		}
		Packet reference = Packet.decode(Packet.preparation(event).encode());
		check(reference.resolvePreparation(firstProfile, secondProfile).sameAs(event),
			"reference preserves full stored event");
		DemoProfile changed = new DemoProfile(firstProfile.userId, firstProfile.keyring, false, true, true, false,
			firstProfile.age, firstProfile.personality(), firstProfile.desired());
		expectFailure(() -> reference.resolvePreparation(changed, secondProfile));
		byte[] capability = Packet.hello(firstProfile.keyring, true).encode();
		capability[capability.length - 1] = 2;
		expectFailure(() -> Packet.decode(capability));
	}

	private static void run(int count, int legacyCount, double loss, int cadence) {
		now = 1;
		List<Node> nodes = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			nodes.add(new Node(index, count, index >= legacyCount));
		}
		Random random = new Random(randomSeed);
		List<Delivery> network = new ArrayList<>();
		long nextTick = 0;
		boolean complete = false;
		for (; now <= 1800000; now += cadence) {
			for (Node sender : nodes) {
				for (boolean full : new boolean[] {false, true}) {
					if (full && !sender.extended) {
						continue;
					}
					byte[] frame = sender.out.nextFrame(full, now, WALL + now);
					check(frame.length + 18 <= (full ? 251 : 31), "radio size ceiling");
					for (Node receiver : nodes) {
						if (sender != receiver && (!full || receiver.extended) && random.nextDouble() >= loss) {
							network.add(new Delivery(sender, receiver, frame, now + random.nextInt(3) * cadence));
						}
					}
				}
			}
			Collections.shuffle(network, random);
			List<Delivery> ready = network.stream().filter(value -> value.at <= now).toList();
			network.removeAll(ready);
			for (Delivery delivery : ready) {
				delivery.receiver.accept(delivery.sender, delivery.frame);
				// The same RF report can be delivered twice by the scanner.
				delivery.receiver.accept(delivery.sender, delivery.frame);
			}
			if (now >= nextTick) {
				nextTick = now + 2000;
				for (Node node : nodes) {
					for (ContractEngine engine : new ArrayList<>(node.engines.values())) {
						engine.tick();
					}
				}
			}
			complete = nodes.stream().allMatch(node -> node.store.confirmed() == count - 1
				&& node.store.feedback.size() == count - 1);
			if (complete) {
				break;
			}
		}
		System.out.println("RADIO nodes=" + count + " legacy=" + legacyCount + " loss=" + loss + " seed=" + randomSeed
			+ " cadence_ms=" + cadence + " simulated_ms=" + now + " complete=" + complete);
		for (Node node : nodes) {
			System.out.println("  node=" + node.index + " confirmed=" + node.store.confirmed()
				+ " feedback=" + node.store.feedback.size() + " queues=" + node.algorithms.size() + "/"
				+ node.queue.size() + " stages=" + node.stages + " errors=" + node.errors);
		}
		check(complete, "all pairs finish; nodes=" + count + "; legacy=" + legacyCount);
		for (Node node : nodes) {
			check(node.errors.keySet().stream().allMatch(code -> code.equals("PREPARE_PRECONDITION_FAILED")),
				"only delayed preparation can be deferred; unexpected errors=" + node.errors);
			check(node.store.list().size() == count - 1, "no duplicate encounters");
			check(node.stages.getOrDefault("FEEDBACK_READY", 0) == count - 1, "one feedback claim per peer");
			check(node.stages.getOrDefault("MATCH_EVALUATED", 0) == count - 1, "one queued evaluation per profile");
			check(node.algorithms.size() == 0 && node.queue.size() == 0, "both waiting queues drain");
			for (SavedEncounter record : node.store.list()) {
				Node peer = nodes.stream().filter(value -> value.id.equals(record.encounter.peerOf(node.id)))
					.findFirst().orElseThrow();
				SavedEncounter other = peer.store.find(record.encounter.eventId);
				check(other != null && record.encounter.sameAs(other.encounter), "both immutable originals equal");
				check(!record.submissionId.equals(other.submissionId), "distinct per-device submissions");
				check(!record.evidence.payload.equals(other.evidence.payload), "distinct per-device proofs");
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void expectFailure(Runnable operation) {
		try {
			operation.run();
			throw new AssertionError("invalid data accepted");
		} catch (IllegalArgumentException expected) {
			// A malformed/oversized frame must never reach either matching queue.
		}
	}

	private static final class Delivery {
		private final Node sender;
		private final Node receiver;
		private final byte[] frame;
		private final long at;

		private Delivery(Node sender, Node receiver, byte[] frame, long at) {
			this.sender = sender;
			this.receiver = receiver;
			this.frame = frame;
			this.at = at;
		}
	}

	private static final class Node {
		private final int index;
		private final boolean extended;
		private final UUID id;
		private final long session;
		private final DemoProfile profile;
		private final AdvertisementCodec codec = new AdvertisementCodec(GROUP);
		private final AdvertisementFrames fragments = new AdvertisementFrames();
		private final RadioOutbox out;
		private final Store store;
		private final CandidateQueue queue = new CandidateQueue();
		private final AlgorithmQueue algorithms = new AlgorithmQueue();
		private final Map<UUID, ContractEngine> engines = new LinkedHashMap<>();
		private final Set<String> seen = new HashSet<>();
		private final Map<String, Integer> stages = new LinkedHashMap<>();
		private final Map<String, Integer> errors = new LinkedHashMap<>();
		private final Set<UUID> evaluated = new HashSet<>();
		private final Set<UUID> queued = new HashSet<>();

		private Node(int index, int count, boolean extended) {
			this.index = index;
			this.extended = extended;
			id = new UUID(0, index + 1);
			session = index + 1;
			profile = new DemoProfile(new UUID(1, count - index), new Peer(id, new UUID(2, index + 1), 1, true),
				true, true, true, false, 25, new byte[] {0, 20, 40, 60, 80}, new byte[5]);
			store = new Store(id);
			out = new RadioOutbox(codec, profile.keyring, session);
			out.configureExtended(extended, extended ? 255 : 31);
			out.setExtendedReady();
		}

		private void accept(Node sender, byte[] frame) {
			byte[] encrypted = frame[0] == ExtendedAdvertisement.MARKER ? ExtendedAdvertisement.unwrap(frame)
				: fragments.accept(sender.id.toString(), frame, now);
			if (encrypted == null) {
				return;
			}
			AdvertisementCodec.Envelope envelope = codec.open(encrypted, WALL + now);
			if (!envelope.forSession(id, session) || !seen.add(envelope.messageId)) {
				return;
			}
			if (envelope.packet.type == Packet.HELLO) {
				out.remember(envelope.source, envelope.sourceSession, envelope.packet.extended);
				if (!engines.containsKey(envelope.source)) {
					ContractEngine engine = new ContractEngine(profile, envelope.source, store, queue,
						MatchingRule.alwaysMatch(),
						packet -> out.send(envelope.source, envelope.sourceSession, packet, now),
						this::observe, () -> WALL + now, () -> now, user -> true, algorithms);
					engines.put(envelope.source, engine);
					engine.setFastRadio(out.usesExtended(envelope.source, envelope.sourceSession));
					engine.start();
				}
			}
			ContractEngine engine = engines.get(envelope.source);
			if (engine != null) {
				try {
					engine.receive(envelope.packet, now - Math.max(0, WALL + now - envelope.sentAt));
					out.acknowledge(envelope.source, envelope.packet);
				} catch (IllegalArgumentException failure) {
					errors.merge(failure.getMessage(), 1, Integer::sum);
				}
			}
		}

		private void observe(FlowEvent event) {
			stages.merge(event.code, 1, Integer::sum);
			if (event.code.equals("ALGORITHM_QUEUE")) {
				queued.add(event.peer);
			}
			if (event.code.equals("MATCH_EVALUATED")) {
				check(queued.contains(event.peer), "calculation follows algorithm queue");
				evaluated.add(event.peer);
			}
			if (event.code.equals("CONFIRMATION_QUEUE")) {
				check(evaluated.contains(event.peer), "confirmation follows calculation");
			}
		}
	}

	private static final class Store implements ContractStore {
		private final UUID device;
		private final Map<UUID, SavedEncounter> records = new LinkedHashMap<>();
		private final Map<UUID, String> feedback = new HashMap<>();

		private Store(UUID device) {
			this.device = device;
		}

		@Override
		public SavedEncounter find(UUID event) {
			return records.get(event);
		}

		@Override
		public SavedEncounter findPair(UUID first, UUID second) {
			return records.values().stream().filter(record -> record.encounter.pairKey().equals(
				first.toString().compareTo(second.toString()) < 0 ? first + ":" + second : second + ":" + first))
				.findFirst().orElse(null);
		}

		@Override
		public SavedEncounter save(Encounter event, Phase phase) {
			SavedEncounter previous = records.get(event.eventId);
			check(previous == null || previous.encounter.sameAs(event), "immutable event on retransmission");
			UUID submission = previous == null ? UUID.randomUUID() : previous.submissionId;
			SavedEncounter record = new SavedEncounter(event, submission,
				previous == null ? phase : SavedEncounter.advance(previous.phase, phase),
				previous == null ? LocalEvidence.create(event, device, submission) : previous.evidence);
			records.put(event.eventId, record);
			return record;
		}

		@Override
		public List<SavedEncounter> list() {
			return new ArrayList<>(records.values());
		}

		private long confirmed() {
			return records.values().stream().filter(record -> record.phase == Phase.CONFIRMED).count();
		}

		@Override
		public boolean claimFeedback(UUID event, String state) {
			return feedback.putIfAbsent(event, state) == null;
		}

		@Override
		public void finishFeedback(UUID event, String state) {
			feedback.put(event, state);
		}

		@Override
		public String feedbackState(UUID event) {
			return feedback.getOrDefault(event, "");
		}
	}
}

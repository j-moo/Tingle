package com.e106.keyring.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;

import com.e106.keyring.core.SavedEncounter.Phase;

/** Dependency-free executable tests; failed expectations throw regardless of JVM assertion settings. */
public final class CoreTest {
	private static int passed;

	private CoreTest() {
	}

	public static void main(String[] args) {
		run("two peers store same event and distinct submission IDs", CoreTest::normal);
		run("lost PROPOSE, READY, COMMIT or STORED survives restart", CoreTest::lossAndRestart);
		run("all messages duplicated without new events", CoreTest::duplicate);
		run("storage failure at every phase sends no success ACK", CoreTest::storageFailure);
		run("new encounters require both peers ready", CoreTest::readiness);
		run("conflicting payload and wrong peer cannot overwrite", CoreTest::conflict);
		run("role swap retries old event without changing owner", CoreTest::roleSwap);
		run("PING/PONG correlation", CoreTest::ping);
		run("early HELLO and ERROR cannot deadlock handshake", CoreTest::handshake);
		run("MTU 23 fragmentation across all lengths", CoreTest::fragmentation);
		run("invalid, oversized, reordered and expired frames rejected", CoreTest::invalidFrames);
		run("wire version, truncation, trailing bytes rejected", CoreTest::invalidPackets);
		run("new encounters accumulate; retries, simultaneous requests and restarts deduplicate", CoreTest::multiple);
		run("group authentication, ciphertext tampering, recipient and session isolation", CoreTest::authentication);
		run("one-way clock skew is diagnosed without weakening authentication or freshness", CoreTest::clockSkew);
		run("advertising fragments tolerate loss, duplicates, reordering and mixed senders", CoreTest::advertisements);
		run("automatic discovery stores once per ON session, including discovery eviction", CoreTest::automatic);
		run("automatic recovery of every lost storage response uses the original event", CoreTest::automaticLoss);
		run("automatic storage failure backs off without false ACK or duplicate records", CoreTest::automaticFailure);
		run("automatic discovery waits for setup; a manual test still adds a new encounter",
			CoreTest::automaticReadiness);
		run("one-sided and simultaneous ON restarts create a new shared encounter",
			CoreTest::automaticSessions);
		run("advertisement scheduler recovers lost discovery fragments and preserves retry progress",
			CoreTest::outbox);
		run("late fourth-round fragments recover HELLO and directed data before bounded expiry",
			CoreTest::lateFragmentRecovery);
		run("slow discovery retains partial progress past ninety seconds within authenticated expiry",
			CoreTest::slowRecoveryWindow);
		run("partial assembly progress distinguishes duplicates, senders and expiry", CoreTest::assemblyProgress);
		run("encrypted chunks recover from repair-only frames across every supported length",
			CoreTest::repairFrames);
		run("asymmetric hardware-like fragment loss still confirms the same event", CoreTest::asymmetricRadio);
		run("lossy fragmented radio recovers missing HELLO and every encounter phase", CoreTest::lossyRadio);
		run("transmitted and received message identities match across fragments and distinguish new sends",
			CoreTest::messageIdentity);
		run("face-up neutral and inverse tilt survive display rotation", CoreTest::motion);
		run("two synchronized pulses have one-second onset spacing and never repeat", CoreTest::feedback);
		run("completion feedback occurs once per peer until local OFF and ON", CoreTest::feedbackGate);
		System.out.println("PASS: " + passed + " scenario groups");
	}

	private static void motion() {
		for (int rotation = 0; rotation < 4; rotation++) {
			float[] neutral = KeyringMotion.fromGravity(0, 0, 9.81f, rotation);
			check(neutral[0] == 0 && neutral[1] == 0, "flat phone is neutral in every display rotation");
		}
		float[] upright = KeyringMotion.fromGravity(0, 6, 8, 0);
		float[] upside = KeyringMotion.fromGravity(0, -6, 8, 0);
		check(upright[0] < 0 && upright[0] == -upside[0], "physical pitch is counter-rotated symmetrically");
		float[] landscape = KeyringMotion.fromGravity(-6, 0, 8, 1);
		check(Arrays.equals(upright, landscape), "landscape tablet uses display coordinates");
		float[] opposite = KeyringMotion.fromGravity(6, 0, 8, 3);
		check(upright[0] == opposite[0] && upright[1] == opposite[1],
			"opposite landscape orientation uses the same reference");
		float[] extreme = KeyringMotion.fromGravity(0, 9.81f, 0, 0);
		check(Math.abs(extreme[0]) <= 28 && Float.isFinite(extreme[1]), "standing phone remains visible");
	}

	private static void feedback() {
		int pulses = 0;
		int activeMillis = 0;
		boolean previous = false;
		for (int millis = -1; millis < 10000; millis++) {
			boolean active = KeyringMotion.isPulse(millis);
			if (active && !previous) {
				check(millis == 0 || millis == 1000, "pulse onset at zero and one second");
				pulses++;
			}
			activeMillis += active ? 1 : 0;
			previous = active;
		}
		check(pulses == 2 && activeMillis == 1000, "two 500ms pulses only");
		long[] waveform = KeyringMotion.vibrationPattern();
		check(Arrays.equals(waveform, new long[] {0, 500, 500, 500}), "500ms on, off, on");
		check(waveform[0] == 0 && waveform[1] + waveform[2] == 1000,
			"hardware and visual feedback share the same onset spacing");
		check(Arrays.stream(waveform).sum() == KeyringMotion.FEEDBACK_MS, "same ending for hardware and screen");
	}

	private static void feedbackGate() {
		FeedbackGate gate = new FeedbackGate();
		UUID peer = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		check(!gate.claim(peer, 0), "no feedback while OFF");
		check(gate.claim(peer, 11), "first completion alerts");
		for (int index = 0; index < 100; index++) {
			check(!gate.claim(peer, 11), "retries, PONGs and new encounters from the same peer stay silent");
		}
		check(gate.claim(other, 11), "another peer has independent feedback");
		check(!gate.claim(peer, 11), "peer rediscovery does not reset local latch");
		check(gate.claim(peer, 22) && gate.claim(other, 22), "local OFF and ON rearm each peer once");
		check(!gate.claim(peer, 22), "second local ON still deduplicates");
	}

	private static void run(String name, Runnable test) {
		test.run();
		passed++;
		System.out.println("PASS " + name);
	}

	private static void normal() {
		Harness harness = new Harness();
		harness.start();
		harness.first.saveOrRetry();
		harness.drain();
		harness.assertConfirmed();
		SavedEncounter first = harness.left.list().get(0);
		SavedEncounter second = harness.right.list().get(0);
		check(!first.submissionId.equals(second.submissionId), "per-device submission IDs");
		check(first.encounter.occurredAt == 1000000L, "original occurrence time");
		for (int index = 0; index < 20; index++) {
			harness.first.saveOrRetry();
			harness.drain();
		}
		check(harness.left.list().get(0).submissionId.equals(first.submissionId), "stable submission ID");
		harness.assertConfirmed();
	}

	private static void lossAndRestart() {
		for (int dropped : new int[] {Packet.PROPOSE, Packet.READY, Packet.COMMIT, Packet.STORED}) {
			Harness harness = new Harness();
			harness.start();
			harness.dropType = dropped;
			harness.first.saveOrRetry();
			harness.drain();
			Encounter original = harness.left.list().get(0).encounter;
			check(harness.left.list().get(0).phase != Phase.CONFIRMED, "no false completion");
			harness.disconnect();
			harness.makeEngines(false);
			harness.dropType = 0;
			harness.start();
			if (dropped == Packet.PROPOSE) {
				// The new recipient must prepare after boot before it accepts its first durable record.
				expectFailure(() -> {
					harness.first.saveOrRetry();
					harness.drain();
				});
				harness.second.updateLocal(harness.peerB);
				harness.first.updateLocal(harness.peerA);
				harness.drain();
			}
			harness.first.saveOrRetry();
			harness.drain();
			harness.assertConfirmed();
			check(original.sameAs(harness.left.list().get(0).encounter), "immutable event after restart");
		}
	}

	private static void duplicate() {
		Harness harness = new Harness();
		harness.start();
		harness.duplicate = true;
		harness.first.saveOrRetry();
		harness.drain();
		harness.assertConfirmed();
	}

	private static void storageFailure() {
		for (int phase = 0; phase < 5; phase++) {
			Harness harness = new Harness();
			harness.start();
			if (phase == 0) {
				harness.left.failPhase = Phase.PREPARED;
			} else if (phase == 1) {
				harness.right.failPhase = Phase.PREPARED;
			} else if (phase == 2) {
				harness.left.failPhase = Phase.COMMITTED;
			} else if (phase == 3) {
				harness.right.failPhase = Phase.CONFIRMED;
			} else {
				harness.left.failPhase = Phase.CONFIRMED;
			}
			expectFailure(() -> {
				harness.first.saveOrRetry();
				harness.drain();
			});
			check(harness.queue.isEmpty(), "no success ACK after failed durable write");
			check(harness.left.list().isEmpty() || harness.left.list().get(0).phase != Phase.CONFIRMED,
				"not falsely confirmed");
			harness.left.failPhase = null;
			harness.right.failPhase = null;
			harness.first.saveOrRetry();
			harness.drain();
			harness.assertConfirmed();
		}
	}

	private static void readiness() {
		Harness harness = new Harness();
		harness.makeEngines(false);
		harness.start();
		expectFailure(harness.first::saveOrRetry);
		harness.first.updateLocal(harness.peerA);
		harness.drain();
		expectFailure(harness.first::saveOrRetry);
		harness.second.updateLocal(harness.peerB);
		harness.drain();
		harness.first.saveOrRetry();
		harness.drain();
		harness.assertConfirmed();
	}

	private static void conflict() {
		Harness harness = new Harness();
		harness.start();
		harness.first.saveOrRetry();
		harness.drain();
		Encounter original = harness.left.list().get(0).encounter;
		Encounter changed = new Encounter(original.eventId, harness.peerA, harness.peerB, original.occurredAt + 1);
		expectFailure(() -> harness.second.receive(Packet.event(Packet.PROPOSE, changed)));
		Encounter wrong = new Encounter(UUID.randomUUID(), harness.peerA,
			new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true), original.occurredAt);
		expectFailure(() -> harness.second.receive(Packet.event(Packet.PROPOSE, wrong)));
		harness.assertConfirmed();
		check(original.sameAs(harness.right.list().get(0).encounter), "original evidence preserved");
	}

	private static void roleSwap() {
		Harness harness = new Harness();
		harness.start();
		harness.dropType = Packet.COMMIT;
		harness.first.saveOrRetry();
		harness.drain();
		harness.disconnect();
		harness.makeEngines(false);
		harness.dropType = 0;
		harness.first.connect(false);
		harness.second.connect(true);
		harness.drain();
		harness.second.saveOrRetry();
		harness.drain();
		harness.assertConfirmed();
	}

	private static void ping() {
		Harness harness = new Harness();
		harness.start();
		harness.first.receive(Packet.ping(Packet.PONG, UUID.randomUUID()));
		check(harness.log.stream().noneMatch(value -> value.contains("시험 성공")), "ignore unsolicited PONG");
		harness.first.ping();
		harness.drain();
		check(harness.log.stream().anyMatch(value -> value.contains("시험 성공")), "correlated PONG");
	}

	private static void fragmentation() {
		for (int length = 1; length <= Packet.MAX_BYTES; length++) {
			byte[] bytes = new byte[length];
			for (int index = 0; index < length; index++) {
				bytes[index] = (byte)(index * 7);
			}
			Frames receiver = new Frames();
			byte[] result = null;
			for (byte[] frame : Frames.split(bytes, 65535)) {
				check(frame.length <= 20, "fits MTU 23");
				result = receiver.accept(frame, 100);
			}
			check(Arrays.equals(bytes, result), "fragment roundtrip " + length);
		}
	}

	private static void handshake() {
		Harness harness = new Harness();
		harness.second.connect(false);
		harness.drain();
		// B's first HELLO arrives before A's subscription callback activates the engine.
		harness.first.connect(true);
		harness.drain();
		check(harness.first.getRemote() != null && harness.second.getRemote() != null, "HELLO recovery");
		harness.disconnect();
		harness.first.connect(true);
		harness.queue.clear();
		harness.first.receive(Packet.error("INVALID_LAB_MESSAGE"));
		check(harness.queue.isEmpty(), "ERROR does not generate ERROR loops before HELLO");
	}

	private static void invalidFrames() {
		List<byte[]> frames = Frames.split(new byte[60], 1);
		expectFailure(() -> new Frames().accept(new byte[7], 0));
		expectFailure(() -> new Frames().accept(frames.get(1), 0));
		Frames receiver = new Frames();
		receiver.accept(frames.get(0), 0);
		expectFailure(() -> receiver.accept(frames.get(0), 1));
		receiver.accept(frames.get(0), 0);
		expectFailure(() -> receiver.accept(frames.get(1), 16000));
		expectFailure(() -> Frames.split(new byte[513], 1));
		byte[] oversized = frames.get(0).clone();
		oversized[6] = 41;
		expectFailure(() -> receiver.accept(oversized, 0));
	}

	private static void invalidPackets() {
		byte[] packet = Packet.ping(Packet.PING, UUID.randomUUID()).encode();
		expectFailure(() -> Packet.decode(Arrays.copyOf(packet, packet.length - 1)));
		expectFailure(() -> Packet.decode(Arrays.copyOf(packet, packet.length + 1)));
		packet[0] = 2;
		expectFailure(() -> Packet.decode(packet));
		expectFailure(() -> Packet.decode(new byte[513]));
	}

	private static void multiple() {
		Harness harness = new Harness();
		harness.start();
		harness.first.startNew();
		harness.drain();
		Encounter original = harness.left.list().get(0).encounter;
		harness.second.startNew();
		harness.duplicate = true;
		harness.drain();
		check(harness.left.list().size() == 2 && harness.right.list().size() == 2, "second encounter added");
		harness.first.startNew();
		harness.second.startNew();
		harness.drain();
		check(harness.left.list().size() == 3 && harness.right.list().size() == 3, "concurrent intent coalesces");
		harness.first.receive(Packet.ping(Packet.NEW_REQUEST, original.eventId));
		harness.drain();
		harness.disconnect();
		harness.makeEngines(true);
		harness.start();
		harness.second.retryLast();
		harness.drain();
		check(harness.left.list().size() == 3, "old request replay and restart do not add records");
		for (SavedEncounter record : harness.left.list()) {
			check(record.phase == Phase.CONFIRMED, "all encounters confirmed");
			check(record.encounter.sameAs(harness.right.find(record.encounter.eventId).encounter), "same evidence");
		}
		harness.dropType = Packet.COMMIT;
		harness.first.startNew();
		harness.drain();
		expectFailure(harness.first::startNew);
		check(harness.left.list().size() == 4, "pending encounter blocks another new encounter");
	}

	private static void authentication() {
		String code = AdvertisementCodec.newGroup();
		AdvertisementCodec sender = new AdvertisementCodec(code);
		AdvertisementCodec receiver = new AdvertisementCodec(code.toLowerCase(java.util.Locale.ROOT));
		UUID source = UUID.randomUUID();
		UUID target = UUID.randomUUID();
		byte[] encrypted = sender.seal(source, 1, target, 2, Packet.ping(Packet.PING, UUID.randomUUID()), 1000000);
		AdvertisementCodec.Envelope envelope = receiver.open(encrypted, 1000010);
		check(envelope.forSession(target, 2), "correct group and receiver accepted");
		check(!envelope.forSession(target, 3), "prior radio session rejected");
		check(!envelope.forSession(UUID.randomUUID(), 2), "third group member cannot act as recipient");
		expectFailure(() -> new AdvertisementCodec(AdvertisementCodec.newGroup()).open(encrypted, 1000010));
		expectFailure(() -> receiver.open(encrypted, 1300000));
		expectFailure(() -> receiver.open(encrypted, 900000));
		byte[] altered = encrypted.clone();
		altered[altered.length - 1] ^= 1;
		expectFailure(() -> receiver.open(altered, 1000010));
		expectFailure(() -> new AdvertisementCodec(""));
		byte[] broadcast = sender.seal(source, 1, AdvertisementCodec.BROADCAST, 0,
			Packet.ping(Packet.PING, UUID.randomUUID()), 1000000);
		expectFailure(() -> receiver.open(broadcast, 1000010));
		byte[] hello = sender.seal(source, 1, AdvertisementCodec.BROADCAST, 0,
			Packet.hello(new Peer(source, UUID.randomUUID(), 1, true)), 1000000);
		check(receiver.open(hello, 1000010).forSession(target, 22), "authenticated discovery");
	}

	private static void clockSkew() {
		AdvertisementCodec codec = new AdvertisementCodec(AdvertisementCodec.newGroup());
		Peer phone = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		Peer tablet = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		long phoneTime = 1000000;
		long tabletTime = phoneTime + 60000;
		byte[] fromPhone = codec.seal(phone.deviceId, 1, AdvertisementCodec.BROADCAST, 0,
			Packet.hello(phone), phoneTime);
		byte[] fromTablet = codec.seal(tablet.deviceId, 2, AdvertisementCodec.BROADCAST, 0,
			Packet.hello(tablet), tabletTime);
		check(codec.open(fromPhone, tabletTime).source.equals(phone.deviceId), "ahead tablet receives phone");
		checkClockFailure(codec, fromTablet, phoneTime, true);
		checkClockFailure(codec, fromPhone, phoneTime + AdvertisementCodec.MAX_AGE_MS + 1, false);
		check(codec.open(fromTablet, tabletTime - 30000) != null, "future tolerance boundary retained");
		checkClockFailure(codec, fromTablet, tabletTime - 30001, true);
		check(codec.open(fromPhone, phoneTime + AdvertisementCodec.MAX_AGE_MS) != null, "expiry boundary retained");
		check(codec.open(fromTablet, tabletTime).source.equals(tablet.deviceId), "aligned clock restores receipt");
		AdvertisementCodec foreign = new AdvertisementCodec(AdvertisementCodec.newGroup());
		try {
			foreign.open(fromTablet, phoneTime);
			throw new AssertionError("foreign group accepted");
		} catch (AdvertisementCodec.ClockException exception) {
			throw new AssertionError("foreign timestamp must not be trusted", exception);
		} catch (IllegalArgumentException expected) {
			check(expected.getMessage().equals("GROUP_AUTHENTICATION_FAILED"), "authentication checked first");
		}
	}

	private static void checkClockFailure(AdvertisementCodec codec, byte[] encrypted, long now, boolean future) {
		try {
			codec.open(encrypted, now);
			throw new AssertionError("invalid time accepted");
		} catch (AdvertisementCodec.ClockException expected) {
			check(expected.future == future, "future and expired messages are distinguished");
		}
	}

	private static void advertisements() {
		for (int size = 1; size <= 596; size++) {
			byte[] data = new byte[size];
			Arrays.fill(data, (byte)size);
			List<byte[]> fragments = AdvertisementFrames.split(data, size);
			AdvertisementFrames receiver = new AdvertisementFrames();
			byte[] result = null;
			for (int index = fragments.size() - 1; index >= 0; index--) {
				byte[] frame = fragments.get(index);
				check(frame.length + 18 <= 31, "fits non-connectable legacy advertising");
				result = receiver.accept("peer-a", frame, 1000);
				check(receiver.accept("peer-a", frame, 1001) == null, "duplicate ignored");
			}
			check(Arrays.equals(data, result), "reordered complete message " + size);
		}
		AdvertisementFrames receiver = new AdvertisementFrames();
		byte[] data = new byte[150];
		List<byte[]> fragments = AdvertisementFrames.split(data, 7);
		for (int index = 1; index < fragments.size(); index++) {
			check(receiver.accept("a", fragments.get(index), 0) == null, "incomplete message not delivered");
		}
		check(receiver.accept("b", fragments.get(0), 0) == null, "senders cannot mix fragments");
		check(Arrays.equals(data, receiver.accept("a", fragments.get(0), 1)), "missing fragment recovered");
		check(receiver.accept("b", fragments.get(1), 90001) == null, "old partial message expires");
		check(receiver.accept("a", new byte[99], 0) == null, "invalid advertising data ignored");
	}

	private static void automatic() {
		Harness harness = new Harness();
		harness.first.onDiscovery(0);
		harness.start();
		// The non-coordinator waits; simultaneous discovery cannot create a second request.
		harness.second.onDiscovery(0);
		check(harness.queue.isEmpty(), "non-coordinator does not send an automatic NEW_REQUEST");
		harness.first.onDiscovery(0);
		harness.second.onDiscovery(0);
		harness.drain();
		harness.assertConfirmed();
		SavedEncounter original = harness.left.list().get(0);
		for (int index = 1; index <= 200; index++) {
			harness.first.receive(Packet.hello(harness.peerB));
			harness.second.receive(Packet.hello(harness.peerA));
			harness.first.onDiscovery(index * 100000L);
			harness.second.onDiscovery(index * 100000L);
			check(harness.queue.isEmpty(), "confirmed beacons create no retransmission traffic");
		}
		harness.disconnect();
		harness.makeEngines(true);
		harness.start();
		harness.first.onDiscovery(0);
		harness.second.onDiscovery(0);
		check(harness.queue.isEmpty(), "same ON sessions retain their event after discovery state reconstruction");
		harness.assertConfirmed();
		check(original.encounter.sameAs(harness.left.list().get(0).encounter)
			&& original.submissionId.equals(harness.left.list().get(0).submissionId), "original evidence retained");
		Peer other = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		List<Packet> outgoing = new ArrayList<>();
		EncounterEngine nextPeer = new EncounterEngine(harness.peerA, harness.left,
			outgoing::add, ignored -> { }, () -> 3000000L);
		nextPeer.connect(true);
		nextPeer.receive(Packet.hello(other));
		nextPeer.onDiscovery(0);
		check(harness.left.list().size() == 2, "latch is per peer, not global");
	}

	private static void automaticLoss() {
		for (int dropped : new int[] {Packet.PROPOSE, Packet.READY, Packet.COMMIT, Packet.STORED}) {
			Harness harness = new Harness();
			harness.start();
			harness.dropType = dropped;
			harness.first.onDiscovery(0);
			harness.drain();
			harness.second.onDiscovery(0);
			harness.drain();
			SavedEncounter original = harness.left.list().get(0);
			check(original.phase != Phase.CONFIRMED, "lost response is not falsely confirmed");
			harness.dropType = 0;
			harness.first.onDiscovery(29999);
			harness.second.onDiscovery(29999);
			check(harness.queue.isEmpty(), "repeated discovery does not restart fragmented messages");
			harness.first.onDiscovery(30000);
			harness.drain();
			harness.assertConfirmed();
			check(original.encounter.sameAs(harness.left.list().get(0).encounter), "automatic retry preserves event");
			// Repeat with a process restart while the next explicitly requested test is unfinished.
			harness.dropType = dropped;
			harness.first.startNew();
			harness.drain();
			SavedEncounter unfinished = harness.left.findPendingPair(harness.peerA.deviceId, harness.peerB.deviceId);
			harness.disconnect();
			harness.makeEngines(true);
			harness.start();
			harness.dropType = 0;
			harness.first.onDiscovery(0);
			harness.second.onDiscovery(0);
			harness.drain();
			check(harness.left.list().size() == 2 && harness.right.list().size() == 2,
				"restart resumes unfinished record instead of adding another");
			check(harness.left.find(unfinished.encounter.eventId).phase == Phase.CONFIRMED,
				"pending record takes priority over old confirmed record");
			check(unfinished.submissionId.equals(harness.left.find(unfinished.encounter.eventId).submissionId),
				"restart preserves pending submission ID");
		}
	}

	private static void automaticFailure() {
		for (int phase = 0; phase < 5; phase++) {
			Harness harness = new Harness();
			harness.start();
			if (phase == 0) {
				harness.left.failPhase = Phase.PREPARED;
			} else if (phase == 1) {
				harness.right.failPhase = Phase.PREPARED;
			} else if (phase == 2) {
				harness.left.failPhase = Phase.COMMITTED;
			} else if (phase == 3) {
				harness.right.failPhase = Phase.CONFIRMED;
			} else {
				harness.left.failPhase = Phase.CONFIRMED;
			}
			expectFailure(() -> {
				harness.first.onDiscovery(0);
				harness.drain();
			});
			check(harness.queue.isEmpty(), "automatic write failure sends no success response");
			SavedEncounter original = harness.left.findPair(harness.peerA.deviceId, harness.peerB.deviceId);
			harness.first.onDiscovery(29999);
			check(harness.queue.isEmpty(), "storage errors do not cause a write storm");
			harness.left.failPhase = null;
			harness.right.failPhase = null;
			harness.first.onDiscovery(30000);
			harness.drain();
			harness.assertConfirmed();
			check(original == null || original.encounter.sameAs(harness.left.list().get(0).encounter),
				"failed phase retry retains saved evidence");
		}
	}

	private static void automaticReadiness() {
		Harness harness = new Harness();
		harness.makeEngines(false);
		harness.start();
		harness.first.onDiscovery(0);
		harness.second.onDiscovery(0);
		check(harness.left.list().isEmpty() && harness.queue.isEmpty(), "unprepared peers do not auto save");
		harness.first.updateLocal(harness.peerA);
		harness.drain();
		harness.first.onDiscovery(0);
		check(harness.left.list().isEmpty(), "one ready peer is insufficient");
		harness.second.updateLocal(harness.peerB);
		harness.drain();
		harness.first.onDiscovery(0);
		harness.drain();
		harness.assertConfirmed();
		for (int index = 0; index < 2; index++) {
			harness.second.startNew();
			harness.first.onDiscovery(100000L * (index + 1));
			harness.second.onDiscovery(100000L * (index + 1));
			harness.drain();
			check(harness.left.list().size() == index + 2 && harness.right.list().size() == index + 2,
				"only explicit new test adds another record");
		}
	}

	private static void automaticSessions() {
		Harness harness = new Harness();
		for (int cycle = 0; cycle < 4; cycle++) {
			harness.start();
			harness.first.onDiscovery(0);
			harness.second.onDiscovery(0);
			harness.drain();
			for (int tick = 1; tick <= 30; tick++) {
				harness.first.onDiscovery(tick * 30000L);
				harness.second.onDiscovery(tick * 30000L);
				harness.drain();
			}
			check(harness.left.list().size() == cycle + 1 && harness.right.list().size() == cycle + 1,
				"exactly one additional event per ON session pair");
			for (SavedEncounter saved : harness.left.list()) {
				SavedEncounter other = harness.right.find(saved.encounter.eventId);
				check(saved.phase == Phase.CONFIRMED && other.phase == Phase.CONFIRMED, "both confirmed");
				check(saved.encounter.sameAs(other.encounter), "same immutable event on both devices");
			}
			check(EncounterEngine.sessionEventId(harness.peerA.deviceId, harness.sessionA,
				harness.peerB.deviceId, harness.sessionB).equals(EncounterEngine.sessionEventId(
					harness.peerB.deviceId, harness.sessionB, harness.peerA.deviceId, harness.sessionA)),
				"both devices derive the same session event");
			harness.disconnect();
			if (cycle != 1) {
				harness.sessionA++;
			}
			if (cycle != 0) {
				harness.sessionB++;
			}
			harness.makeEngines(true);
		}
	}

	private static void outbox() {
		Peer peer = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		AdvertisementCodec codec = new AdvertisementCodec(AdvertisementCodec.newGroup());
		AdvertisementOutbox outgoing = new AdvertisementOutbox(codec, peer, 1);
		AdvertisementFrames receiver = new AdvertisementFrames();
		byte[] first = outgoing.nextFrame(0, 1000000);
		int count = first[5] & 255;
		for (int index = 1; index < count; index++) {
			check(receiver.accept("a", outgoing.nextFrame(index * 300L, 1000000), index * 300L) == null,
				"one missing HELLO fragment prevents discovery");
		}
		byte[] recovered = null;
		for (int index = count; index <= count * 2 && recovered == null; index++) {
			recovered = receiver.accept("a", outgoing.nextFrame(index * 300L, 1000000), index * 300L);
		}
		check(recovered != null && codec.open(recovered, 1000000).packet.type == Packet.HELLO,
			"next discovery round fills the missing fragment instead of replacing the message");
		outgoing = new AdvertisementOutbox(codec, peer, 1);
		UUID recipient = UUID.randomUUID();
		Packet ping = Packet.ping(Packet.PING, UUID.randomUUID());
		outgoing.send(recipient, 2, ping, 0, 1000000);
		first = outgoing.nextFrame(0, 1000000);
		receiver = new AdvertisementFrames();
		receiver.accept("a", first, 0);
		count = first[5] & 255;
		for (int index = 1; index < count; index++) {
			outgoing.send(recipient, 2, ping, index * 300L, 1000000 + index * 300L);
			recovered = receiver.accept("a", outgoing.nextFrame(index * 300L, 1000000), index * 300L);
		}
		check(recovered != null && codec.open(recovered, 1000000).packet.token.equals(ping.token),
			"repeated retries never reset the fragment cursor");
	}

	private static void lateFragmentRecovery() {
		for (boolean directed : new boolean[] {false, true}) {
			Peer peer = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
			AdvertisementCodec codec = new AdvertisementCodec(AdvertisementCodec.newGroup());
			AdvertisementOutbox outbox = new AdvertisementOutbox(codec, peer, 1);
			UUID recipient = UUID.randomUUID();
			Packet ping = Packet.ping(Packet.PING, UUID.randomUUID());
			if (directed) {
				outbox.send(recipient, 2, ping, 0, 1000000);
			}
			byte[] first = outbox.nextFrame(0, 1000000);
			String original = outbox.currentMessage().id;
			int size = first[5] & 255;
			int delivered = 1;
			AdvertisementFrames incoming = new AdvertisementFrames();
			byte[] recovered = null;
			for (long elapsed = 300; elapsed < 90000; elapsed += 300) {
				byte[] frame = outbox.nextFrame(elapsed, 1000000 + elapsed);
				if (!outbox.currentMessage().id.equals(original)) {
					continue;
				}
				delivered++;
				if (delivered <= size * 6 && (frame[0] != AdvertisementFrames.MARKER || (frame[4] & 255) == 0)) {
					continue;
				}
				recovered = incoming.accept("remote", frame, elapsed);
				if (recovered != null) {
					break;
				}
			}
			check(recovered != null && codec.open(recovered, 1090000).messageId.equals(original),
				"missing piece after three rounds completes the original encrypted message");
			if (directed) {
				outbox.acknowledge(recipient, Packet.ping(Packet.PONG, ping.token));
				for (int index = 0; index < 100; index++) {
					outbox.nextFrame(60000 + index * 100, 1060000);
					check(!outbox.currentMessage().id.equals(original), "ACK stops the remaining repeats");
				}
			} else {
				outbox.nextFrame(150000, 1150000);
				check(!outbox.currentMessage().id.equals(original), "expiry still renews nonce and transfer");
			}
		}
	}

	private static void slowRecoveryWindow() {
		Peer peer = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		AdvertisementCodec codec = new AdvertisementCodec(AdvertisementCodec.newGroup());
		AdvertisementOutbox outbox = new AdvertisementOutbox(codec, peer, 1);
		byte[] first = outbox.nextFrame(0, 1000000);
		String message = outbox.currentMessage().id;
		int count = first[5] & 255;
		AdvertisementFrames incoming = new AdvertisementFrames();
		for (int index = 1; index < count; index++) {
			incoming.accept("slow", outbox.nextFrame(index * 300L, 1000000), index * 300L);
		}
		check(incoming.progress(95000).received == count - 1,
			"near-complete assembly is not discarded at ninety seconds");
		outbox.nextFrame(120000, 1120000);
		check(outbox.currentMessage().id.equals(message), "late retries keep the same encrypted transfer");
		byte[] recovered = incoming.accept("slow", first, 120000);
		check(recovered != null && codec.open(recovered, 1120000).messageId.equals(message),
			"a late missing fragment completes the authenticated original message");
		outbox.nextFrame(150000, 1150000);
		check(!outbox.currentMessage().id.equals(message), "sender still renews after the bounded recovery window");
		AdvertisementFrames abandoned = new AdvertisementFrames();
		abandoned.accept("slow", first, 0);
		check(abandoned.progress(150000).pending == 1, "assembly survives through the recovery boundary");
		check(abandoned.progress(150001).pending == 0, "abandoned assembly still expires after the boundary");
	}

	private static void assemblyProgress() {
		AdvertisementFrames incoming = new AdvertisementFrames();
		List<byte[]> frames = AdvertisementFrames.split(new byte[39], 1);
		incoming.accept("a", frames.get(0), 0);
		incoming.accept("a", frames.get(0), 1);
		AdvertisementFrames.Progress progress = incoming.progress(1);
		check(progress.received == 1 && progress.total == 6 && progress.pending == 1,
			"duplicate frame does not inflate progress");
		incoming.accept("b", frames.get(0), 1);
		check(incoming.progress(1).pending == 2, "sender assemblies stay separate");
		for (int index = 1; index < frames.size(); index++) {
			incoming.accept("a", frames.get(index), 2);
		}
		progress = incoming.progress(2);
		check(progress.pending == 1 && progress.received == 1, "completed transfer leaves the pending view");
		check(incoming.progress(150002).pending == 0, "old partial transfers still expire");
	}

	private static void repairFrames() {
		Random random = new Random(106);
		for (int length = 1; length <= 596; length++) {
			byte[] data = new byte[length];
			random.nextBytes(data);
			List<byte[]> originals = AdvertisementFrames.split(data, length);
			List<byte[]> repair = new ArrayList<>();
			for (int seed = 0; seed < 128; seed++) {
				repair.add(AdvertisementRepair.frame(originals, seed));
			}
			java.util.Collections.shuffle(repair, random);
			AdvertisementFrames incoming = new AdvertisementFrames();
			byte[] recovered = null;
			for (byte[] frame : repair) {
				check(frame.length + 18 == 31, "repair fits the same legacy advertisement");
				recovered = incoming.accept("remote", frame, 0);
				if (recovered != null) {
					break;
				}
				int progress = incoming.progress(0).received;
				check(incoming.accept("remote", frame, 0) == null, "duplicate equation does not complete twice");
				check(incoming.progress(0).received == progress, "duplicate equation cannot inflate rank");
			}
			check(Arrays.equals(data, recovered), "repair-only roundtrip including exact final length " + length);
		}
		AdvertisementCodec codec = new AdvertisementCodec(AdvertisementCodec.newGroup());
		Peer peer = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		byte[] encrypted = codec.seal(peer.deviceId, 1, AdvertisementCodec.BROADCAST, 0,
			Packet.hello(peer), 1000000);
		encrypted[20] ^= 1;
		List<byte[]> originals = AdvertisementFrames.split(encrypted, 106);
		AdvertisementFrames incoming = new AdvertisementFrames();
		byte[] recovered = null;
		for (int seed = 0; seed < 128 && recovered == null; seed++) {
			byte[] frame = AdvertisementRepair.frame(originals, seed);
			recovered = incoming.accept("remote", frame, 0);
		}
		check(recovered != null, "tampered equation still requires final cryptographic validation");
		byte[] altered = recovered;
		expectFailure(() -> codec.open(altered, 1000000));
	}

	private static void asymmetricRadio() {
		for (int[] sample : new int[][] {{106, 70, 20}, {124, 70, 20}, {610, 70, 20},
			{106, 85, 40}, {124, 85, 40}, {610, 85, 40}}) {
			int seed = sample[0];
			String group = AdvertisementCodec.newGroup();
			RadioNode phone = new RadioNode(group, new UUID(0, 2), 1);
			RadioNode tablet = new RadioNode(group, new UUID(0, 1), 2);
			Random loss = new Random(seed);
			boolean confirmed = false;
			List<String> transitions = new ArrayList<>();
			String last = "";
			for (long now = 0; now < 900000; now += 300) {
				phone.elapsed = now;
				tablet.elapsed = now;
				byte[] fromPhone = phone.outbox.nextFrame(now, 1000000 + now);
				byte[] fromTablet = tablet.outbox.nextFrame(now, 1000000 + now);
				if (loss.nextInt(100) >= sample[2]) {
					tablet.accept(fromPhone);
				}
				if (loss.nextInt(100) >= sample[1]) {
					phone.accept(fromTablet);
				}
				phone.tick();
				tablet.tick();
				String phases = phone.store.list().stream().map(record -> record.phase.name()).toList()
					+ "/" + tablet.store.list().stream().map(record -> record.phase.name()).toList();
				if (!phases.equals(last)) {
					transitions.add(now + ":" + phases);
					last = phases;
				}
				check(phone.store.list().size() <= 1 && tablet.store.list().size() <= 1, "no false extra event");
				if (phone.store.list().size() == 1 && tablet.store.list().size() == 1
					&& phone.store.list().get(0).phase == Phase.CONFIRMED
					&& tablet.store.list().get(0).phase == Phase.CONFIRMED) {
					check(phone.store.list().get(0).encounter.sameAs(tablet.store.list().get(0).encounter),
						"asymmetric receipt preserves the same event evidence");
					confirmed = true;
					break;
				}
			}
			check(confirmed, sample[1] + " percent vs " + sample[2] + " percent loss must recover, seed "
				+ seed + " " + transitions
				+ " last receipts " + phone.lastSeen + "/" + tablet.lastSeen);
		}
	}

	private static void lossyRadio() {
		for (int drop : new int[] {Packet.HELLO, Packet.PROPOSE, Packet.READY, Packet.COMMIT, Packet.STORED}) {
			String group = AdvertisementCodec.newGroup();
			RadioNode left = new RadioNode(group, new UUID(0, 1), 1);
			RadioNode right = new RadioNode(group, new UUID(0, 2), 2);
			left.dropType = drop;
			right.dropType = drop;
			Random loss = new Random(106 + drop);
			for (long now = 0; now < 600000; now += 300) {
				left.elapsed = now;
				right.elapsed = now;
				byte[] fromLeft = left.outbox.nextFrame(now, 1000000 + now);
				byte[] fromRight = right.outbox.nextFrame(now, 1000000 + now);
				if (loss.nextInt(100) >= 20) {
					right.accept(fromLeft);
				}
				if (loss.nextInt(100) >= 20) {
					left.accept(fromRight);
				}
				left.tick();
				right.tick();
			}
			check(left.store.list().size() == 1 && right.store.list().size() == 1, "loss never creates extra records");
			SavedEncounter first = left.store.list().get(0);
			SavedEncounter second = right.store.list().get(0);
			check(first.phase == Phase.CONFIRMED && second.phase == Phase.CONFIRMED,
				"missing phase " + drop + " recovers with fragment loss");
			check(first.encounter.sameAs(second.encounter), "loss recovery agrees on payload and event ID");
		}
	}

	private static void messageIdentity() {
		Peer peer = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		AdvertisementCodec codec = new AdvertisementCodec(AdvertisementCodec.newGroup());
		AdvertisementOutbox outgoing = new AdvertisementOutbox(codec, peer, 1);
		AdvertisementFrames receiver = new AdvertisementFrames();
		UUID recipient = UUID.randomUUID();
		Packet ping = Packet.ping(Packet.PING, UUID.randomUUID());
		outgoing.send(recipient, 2, ping, 0, 1000000);
		byte[] first = outgoing.nextFrame(0, 1000000);
		AdvertisementOutbox.Message sent = outgoing.currentMessage();
		check(sent.recipient.equals(recipient) && sent.packet.token.equals(ping.token), "TX records intended data");
		receiver.accept("a", first, 0);
		byte[] recovered = null;
		for (int index = 1; index < (first[5] & 255); index++) {
			recovered = receiver.accept("a", outgoing.nextFrame(index * 300L, 1000000), index * 300L);
			check(outgoing.currentMessage().id.equals(sent.id), "fragments share one message ID");
		}
		check(recovered != null && codec.open(recovered, 1000000).messageId.equals(sent.id),
			"receiver and sender log the same message ID");
		outgoing.send(recipient, 2, Packet.ping(Packet.PING, UUID.randomUUID()), 10000, 1010000);
		check(sent.packet.token.equals(ping.token), "pending radio callback retains original packet after replacement");
		for (int index = 0; index < 200; index++) {
			outgoing.nextFrame(10000 + index * 300L, 1010000);
			AdvertisementOutbox.Message current = outgoing.currentMessage();
			if (current.packet.type == Packet.HELLO) {
				check(current.recipient.equals(AdvertisementCodec.BROADCAST), "discovery transmission is logged too");
			} else {
				check(!current.id.equals(sent.id), "replacement packet has a distinct ID");
			}
		}
		AdvertisementOutbox restarted = new AdvertisementOutbox(codec, peer, 3);
		restarted.send(recipient, 2, ping, 0, 1000000);
		restarted.nextFrame(0, 1000000);
		check(!restarted.currentMessage().id.equals(sent.id), "identical content after ON has a new message ID");
	}

	private static final class RadioNode {
		private final Peer peer;
		private final long session;
		private final AdvertisementCodec codec;
		private final AdvertisementOutbox outbox;
		private final AdvertisementFrames incoming = new AdvertisementFrames();
		private final MemoryStore store = new MemoryStore();
		private EncounterEngine engine;
		private long elapsed;
		private long lastSeen;
		private int dropType;

		private RadioNode(String group, UUID id, long session) {
			this.session = session;
			peer = new Peer(id, UUID.randomUUID(), 1, true);
			codec = new AdvertisementCodec(group);
			outbox = new AdvertisementOutbox(codec, peer, session);
		}

		private void accept(byte[] frame) {
			byte[] encrypted = incoming.accept("remote", frame, elapsed);
			if (encrypted == null) {
				return;
			}
			AdvertisementCodec.Envelope envelope = codec.open(encrypted, 1000000 + elapsed);
			if (!envelope.forSession(peer.deviceId, session)) {
				return;
			}
			if (envelope.packet.type == dropType) {
				dropType = 0;
				return;
			}
			if (engine == null && envelope.packet.type == Packet.HELLO) {
				engine = new EncounterEngine(peer, store, packet -> {
					if (packet.type != Packet.HELLO) {
						outbox.send(envelope.source, envelope.sourceSession, packet, elapsed, 1000000 + elapsed);
					}
				}, ignored -> { }, () -> 1000000 + elapsed);
				engine.connect(peer.deviceId.toString().compareTo(envelope.source.toString()) < 0,
					EncounterEngine.sessionEventId(peer.deviceId, session, envelope.source, envelope.sourceSession));
			}
			if (engine != null) {
				lastSeen = elapsed;
				engine.receive(envelope.packet);
				outbox.acknowledge(envelope.source, envelope.packet);
			}
		}

		private void tick() {
			if (engine != null && elapsed - lastSeen <= 120000) {
				engine.onDiscovery(elapsed);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void expectFailure(Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException | IllegalStateException expected) {
			return;
		}
		throw new AssertionError("Expected rejection");
	}

	private static final class MemoryStore implements RecordStore {
		private final Map<UUID, SavedEncounter> records = new LinkedHashMap<>();
		private Phase failPhase;

		@Override
		public SavedEncounter find(UUID eventId) {
			return records.get(eventId);
		}

		@Override
		public SavedEncounter findPair(UUID first, UUID second) {
			return records.values().stream().filter(value -> {
				Encounter event = value.encounter;
				return (event.deviceA.equals(first) && event.deviceB.equals(second))
					|| (event.deviceA.equals(second) && event.deviceB.equals(first));
			}).reduce((older, newer) -> newer).orElse(null);
		}

		@Override
		public SavedEncounter save(Encounter encounter, Phase phase) {
			if (phase == failPhase) {
				throw new IllegalStateException("INJECTED_DISK_FAILURE");
			}
			SavedEncounter old = find(encounter.eventId);
			if ((old != null && !old.encounter.sameAs(encounter))
				|| (old == null && findPendingPair(encounter.deviceA, encounter.deviceB) != null)) {
				throw new IllegalArgumentException("CONFLICT");
			}
			SavedEncounter saved = new SavedEncounter(encounter, old == null ? UUID.randomUUID() : old.submissionId,
				old == null ? phase : SavedEncounter.advance(old.phase, phase));
			records.put(encounter.eventId, saved);
			return saved;
		}

		@Override
		public List<SavedEncounter> list() {
			return new ArrayList<>(records.values());
		}
	}

	private static final class Harness {
		private final Peer peerA = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		private final Peer peerB = new Peer(UUID.randomUUID(), UUID.randomUUID(), 2, true);
		private final MemoryStore left = new MemoryStore();
		private final MemoryStore right = new MemoryStore();
		private final Queue<Runnable> queue = new ArrayDeque<>();
		private final List<String> log = new ArrayList<>();
		private EncounterEngine first;
		private EncounterEngine second;
		private int dropType;
		private boolean duplicate;
		private long sessionA = 1;
		private long sessionB = 2;

		private Harness() {
			makeEngines(true);
		}

		private void makeEngines(boolean ready) {
			first = new EncounterEngine(new Peer(peerA.deviceId, peerA.snapshotId, 1, ready), left,
				packet -> enqueue(false, packet), log::add, () -> 1000000L);
			second = new EncounterEngine(new Peer(peerB.deviceId, peerB.snapshotId, 2, ready), right,
				packet -> enqueue(true, packet), log::add, () -> 2000000L);
		}

		private void enqueue(boolean toFirst, Packet packet) {
			if (packet.type == dropType) {
				return;
			}
			for (int index = 0; index < (duplicate ? 2 : 1); index++) {
				queue.add(() -> (toFirst ? first : second).receive(Packet.decode(packet.encode())));
			}
		}

		private void start() {
			UUID eventId = EncounterEngine.sessionEventId(peerA.deviceId, sessionA, peerB.deviceId, sessionB);
			first.connect(true, eventId);
			second.connect(false, eventId);
			drain();
		}

		private void disconnect() {
			first.disconnect();
			second.disconnect();
			queue.clear();
		}

		private void drain() {
			int limit = 1000;
			while (!queue.isEmpty() && limit-- > 0) {
				queue.remove().run();
			}
			check(limit > 0, "bounded retries");
		}

		private void assertConfirmed() {
			check(left.list().size() == 1 && right.list().size() == 1, "exactly one record per peer");
			check(left.list().get(0).encounter.sameAs(right.list().get(0).encounter), "same evidence");
			check(left.list().get(0).phase == Phase.CONFIRMED, "A confirmed");
			check(right.list().get(0).phase == Phase.CONFIRMED, "B confirmed");
		}
	}
}

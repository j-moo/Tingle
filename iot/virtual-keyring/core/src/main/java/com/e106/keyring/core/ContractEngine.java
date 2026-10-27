package com.e106.keyring.core;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import com.e106.keyring.core.SavedEncounter.Phase;

/** Serial, advertisement-only implementation of the contract's device-side flow. */
public final class ContractEngine {
	public static final long PRESENCE_MS = 150000;
	private static final long RETRY_MS = 30000;
	private final DemoProfile local;
	private final UUID peerId;
	private final ContractStore store;
	private final CandidateQueue queue;
	private final MatchingRule rule;
	private final Consumer<Packet> sender;
	private final Consumer<FlowEvent> observer;
	private final LongSupplier clock;
	private final LongSupplier elapsed;
	private final Predicate<UUID> relationshipAllowed;
	private final AlgorithmQueue algorithms;
	private DemoProfile remote;
	private Packet earlyDecision;
	private UUID context;
	private Boolean localDecision;
	private Boolean remoteDecision;
	private UUID probe;
	private long probeAt;
	private long lastSeen;
	private long nextRetry;
	private long turnAt;
	private boolean started;
	private boolean queued;
	private boolean rechecked;
	private boolean suppressed;
	private boolean stale;
	private UUID eventId;
	private UUID ping;
	private boolean fastRadio;
	private String stage = "WAIT_DISCOVERY";

	public ContractEngine(DemoProfile local, UUID peerId, ContractStore store, CandidateQueue queue,
		MatchingRule rule, Consumer<Packet> sender, Consumer<FlowEvent> observer,
		LongSupplier clock, LongSupplier elapsed) {
		this(local, peerId, store, queue, rule, sender, observer, clock, elapsed, user -> true);
	}

	public ContractEngine(DemoProfile local, UUID peerId, ContractStore store, CandidateQueue queue,
		MatchingRule rule, Consumer<Packet> sender, Consumer<FlowEvent> observer,
		LongSupplier clock, LongSupplier elapsed, Predicate<UUID> relationshipAllowed) {
		this(local, peerId, store, queue, rule, sender, observer, clock, elapsed, relationshipAllowed,
			new AlgorithmQueue());
	}

	public ContractEngine(DemoProfile local, UUID peerId, ContractStore store, CandidateQueue queue,
		MatchingRule rule, Consumer<Packet> sender, Consumer<FlowEvent> observer,
		LongSupplier clock, LongSupplier elapsed, Predicate<UUID> relationshipAllowed, AlgorithmQueue algorithms) {
		this.local = local;
		this.peerId = peerId;
		this.store = store;
		this.queue = queue;
		this.rule = rule;
		this.sender = sender;
		this.observer = observer;
		this.clock = clock;
		this.elapsed = elapsed;
		this.relationshipAllowed = relationshipAllowed;
		this.algorithms = algorithms;
	}

	public DemoProfile remote() {
		return remote;
	}

	public String stage() {
		return stage;
	}

	public void setFastRadio(boolean enabled) {
		fastRadio = enabled;
	}

	private long retryDelay() {
		return fastRadio ? 3000 : RETRY_MS;
	}

	private long turnLease() {
		return fastRadio ? 12000 + Math.floorMod(local.keyring.deviceId.hashCode() ^ peerId.hashCode(), 11) * 1000L
			: PRESENCE_MS;
	}

	public void start() {
		if (started) {
			return;
		}
		started = true;
		lastSeen = elapsed.getAsLong();
		emit("AUTHENTICATED", "authentication=LAB_GROUP_AES_GCM; production_device_identity=false");
		sendProfile();
	}

	private void sendProfile() {
		sender.accept(Packet.profile(local));
		nextRetry = elapsed.getAsLong() + retryDelay();
		emit("MATCH_DATA_QUEUED", "user_id=" + local.userId + "; matching_rule_version=" + DemoProfile.RULE);
	}

	public void receive(Packet packet) {
		receive(packet, elapsed.getAsLong());
	}

	/** observedAt is derived from the authenticated envelope time, not from delayed assembly completion. */
	public void receive(Packet packet, long observedAt) {
		if (!started) {
			return;
		}
		lastSeen = Math.max(lastSeen, observedAt);
		if (packet.type == Packet.HELLO) {
			return;
		}
		if (packet.type == Packet.ERROR) {
			emit("PEER_ERROR", packet.error);
			return;
		}
		if (packet.type == Packet.PING) {
			sender.accept(Packet.ping(Packet.PONG, packet.token));
			emit("RADIO_CHECK", "PING received; PONG queued; no encounter or feedback");
			return;
		}
		if (packet.type == Packet.PONG) {
			if (packet.token.equals(ping)) {
				ping = null;
				emit("RADIO_CHECK", "PING/PONG confirmed; no encounter or feedback");
			}
			return;
		}
		if (packet.type == Packet.MATCH_DATA) {
			onProfile(packet.profile);
		} else if (packet.type == Packet.MATCH_RESULT) {
			if (remote == null) {
				earlyDecision = packet;
				emit("WAIT_MATCH_DATA", "decision received before complete MATCH_DATA");
			} else {
				onDecision(packet);
			}
		} else if (packet.type == Packet.RECHECK_BUSY) {
			onBusy(packet);
		} else if (packet.type == Packet.RECHECK || packet.type == Packet.RECHECK_OK) {
			onRecheck(packet);
		} else if (packet.type == Packet.PREPARE) {
			onPrepare(packet.encounter);
		} else if (packet.type == Packet.PREPARE_REFERENCE) {
			SavedEncounter original = store.find(packet.token);
			Encounter event = original == null ? packet.resolvePreparation(local, remote)
				: packet.resolvePreparation(original.encounter.profileA, original.encounter.profileB);
			onPrepare(event);
		} else if (packet.type >= Packet.PREPARED && packet.type <= Packet.RECEIPT) {
			onDurability(packet);
		} else {
			emit("INCOMPATIBLE_PROTOCOL", "received_type=" + packet.type + "; both devices require 0.12");
		}
	}

	private void onProfile(DemoProfile profile) {
		if (!profile.keyring.deviceId.equals(peerId)) {
			throw new IllegalArgumentException("PROFILE_SENDER_CONFLICT");
		}
		if (remote == null || !remote.sameAs(profile)) {
			queue.remove(peerId);
			queued = false;
			rechecked = false;
			probe = null;
			remoteDecision = null;
			localDecision = null;
			remote = profile;
			context = DemoProfile.context(local, remote);
			emit("MATCH_DATA_RECEIVED", "user_id=" + remote.userId + "; sync_version="
				+ remote.keyring.settingsVersion + "; settings_snapshot_id=" + remote.keyring.snapshotId);
			algorithms.submit(peerId, this::evaluateProfile);
			emit("ALGORITHM_QUEUE", "personality=synthetic; desired=synthetic; order=arrival; size="
				+ algorithms.size());
		}
		if (localDecision != null) {
			sender.accept(Packet.decision(context, localDecision));
		} else {
			algorithms.submit(peerId, this::evaluateProfile);
		}
		if (earlyDecision != null) {
			Packet pending = earlyDecision;
			earlyDecision = null;
			onDecision(pending);
		}
	}

	private void evaluateProfile() {
		if (!started || elapsed.getAsLong() - lastSeen > PRESENCE_MS) {
			return;
		}
		emit("MATCH_EVALUATING", "matching_rule_version=" + local.ruleVersion + "; demo_always_match=true");
		localDecision = eligible() && rule.matches(local, remote);
		emit("MATCH_EVALUATED", "allowed=" + localDecision + "; context=" + context
			+ "; demographic_and_personality_algorithm=DEMO_ALWAYS_MATCH; relation_list=local_only");
		sender.accept(Packet.decision(context, localDecision));
		if (!Boolean.TRUE.equals(localDecision)) {
			emit("CONDITIONS_REJECTED", "self/ready/matching/photo gate rejected; no alert");
			return;
		}
		SavedEncounter previous = existing();
		if (previous != null) {
			eventId = previous.encounter.eventId;
			if (!suppressed) {
				suppressed = true;
				emit("DUPLICATE_SUPPRESSED", "existing_event_id=" + eventId
					+ "; retained across OFF/restart; upload=PENDING_SERVICE_APP");
			}
			retryStored(previous);
		} else {
			queueIfReady();
		}
	}

	private boolean eligible() {
		return remote != null && !local.userId.equals(remote.userId) && local.keyring.ready
			&& remote.keyring.ready && local.matchingEnabled && remote.matchingEnabled
			&& relationshipAllowed.test(remote.userId)
			&& local.policyVersion.equals(remote.policyVersion) && local.ruleVersion.equals(remote.ruleVersion)
			&& local.opportunityMillis == remote.opportunityMillis
			&& (!local.requirePhoto || remote.hasPhoto) && (!remote.requirePhoto || local.hasPhoto);
	}

	private void onDecision(Packet packet) {
		if (!context.equals(packet.context)) {
			emit("STALE_DECISION", "current profile context differs; waiting for current result");
			throw new IllegalArgumentException("STALE_DECISION");
		}
		if (remoteDecision == null || remoteDecision != packet.allowed) {
			remoteDecision = packet.allowed;
			emit("PEER_MATCH_RESULT", "allowed=" + remoteDecision + "; context=" + context);
		}
		if (!packet.allowed) {
			queue.remove(peerId);
			queued = false;
			rechecked = false;
		}
		queueIfReady();
	}

	private void queueIfReady() {
		SavedEncounter pending = existing();
		if (!bilateral() || (pending != null && pending.phase != Phase.PREPARED) || queued) {
			return;
		}
		queued = queue.add(peerId);
		stale = false;
		emit("BOTH_CONDITIONS_PASSED", "both local and peer decisions accepted");
		emit("CONFIRMATION_QUEUE", "order=bilateral_condition_completion; queue_size=" + queue.size());
		nextRetry = 0;
	}

	private boolean bilateral() {
		return eligible() && Boolean.TRUE.equals(localDecision) && Boolean.TRUE.equals(remoteDecision);
	}

	private boolean coordinator() {
		return local.userId.toString().compareTo(remote.userId.toString()) < 0;
	}

	private SavedEncounter existing() {
		return remote == null ? null : store.findContractPair(local.userId, remote.userId);
	}

	public void tick() {
		if (!started) {
			return;
		}
		algorithms.runNext();
		long now = elapsed.getAsLong();
		if (now - lastSeen > PRESENCE_MS) {
			queue.remove(peerId);
			queued = false;
			rechecked = false;
			probe = null;
			if (!stale) {
				stale = true;
				emit("PEER_LEFT", "fresh authenticated presence expired; durable records retained");
			}
			return;
		}
		if (now < nextRetry) {
			return;
		}
		nextRetry = now + retryDelay();
		SavedEncounter previous = existing();
		if (previous != null) {
			retryStored(previous);
			return;
		}
		if (remote == null || remoteDecision == null) {
			sendProfile();
			if (remote != null && localDecision != null) {
				sender.accept(Packet.decision(context, Boolean.TRUE.equals(localDecision)));
			}
			emit("WAIT_RESPONSE", "stage=profile_or_bilateral_result; retry=true; not_matching_failure");
			return;
		}
		queueIfReady();
		if (!bilateral() || !queued) {
			return;
		}
		if (!queue.acquire(peerId)) {
			emit("WAIT_TURN", "one peer at a time; queue_size=" + queue.size());
			return;
		}
		if (turnAt != 0 && now - turnAt > (rechecked ? PRESENCE_MS : turnLease())) {
			queue.remove(peerId);
			queue.add(peerId);
			probe = null;
			rechecked = false;
			turnAt = 0;
			emit("TURN_RETRY", "bounded reservation released; same candidate requeued");
			return;
		}
		if (turnAt == 0) {
			turnAt = now;
		}
		if (coordinator()) {
			if (probe == null || now - probeAt > PRESENCE_MS) {
				probe = UUID.randomUUID();
				probeAt = now;
			}
			sender.accept(Packet.recheck(Packet.RECHECK, probe, context));
			emit("RECHECK_SENT", "fresh challenge=" + probe + "; current conditions rechecked");
		} else {
			emit("WAIT_RECHECK", "waiting for the lower user_id coordinator; no new event yet");
		}
	}

	private void onRecheck(Packet packet) {
		if (!bilateral() || !context.equals(packet.context) || !queued || !queue.acquire(peerId)) {
			if (packet.type == Packet.RECHECK && bilateral() && context.equals(packet.context) && queued) {
				sender.accept(Packet.recheck(Packet.RECHECK_BUSY, packet.token, context));
			}
			emit("RECHECK_DEFERRED", "conditions/context/queue not ready; retry without new event");
			return;
		}
		long now = elapsed.getAsLong();
		if (packet.type == Packet.RECHECK && !coordinator()) {
			probe = packet.token;
			probeAt = now;
			rechecked = true;
			sender.accept(Packet.recheck(Packet.RECHECK_OK, probe, context));
			emit("RECHECK_PASSED", "peer challenge answered; current bilateral conditions valid");
		} else if (packet.type == Packet.RECHECK_OK && coordinator() && packet.token.equals(probe)
			&& now - probeAt <= PRESENCE_MS) {
			rechecked = true;
			emit("RECHECK_PASSED", "fresh challenge response received; current bilateral conditions valid");
			SavedEncounter previous = existing();
			if (previous != null && previous.phase != Phase.PREPARED) {
				return;
			}
			Encounter event = previous == null
				? new Encounter(UUID.randomUUID(), local, remote, clock.getAsLong(), probe) : previous.encounter;
			store.save(event, Phase.PREPARED);
			eventId = event.eventId;
			emit("PREPARED_LOCALLY", "event and original per-device submission evidence durably saved");
			sender.accept(Packet.event(Packet.PREPARE, event));
			nextRetry = now + retryDelay();
		}
	}

	private void onBusy(Packet packet) {
		if (!queued || rechecked || !bilateral() || !coordinator()
			|| !packet.context.equals(context) || !packet.token.equals(probe)) {
			return;
		}
		queue.remove(peerId);
		queue.add(peerId);
		rechecked = false;
		probe = null;
		turnAt = 0;
		nextRetry = elapsed.getAsLong() + (fastRadio ? 1200 : 6000)
			+ Math.floorMod(local.keyring.deviceId.hashCode() ^ peerId.hashCode(), 7) * 600L;
		emit("TURN_RETRY", "peer_busy=true; reservation released; original event retained; candidate requeued");
	}

	private void onPrepare(Encounter event) {
		if (event == null || !event.isContract() || !event.peerOf(local.keyring.deviceId).equals(peerId)) {
			throw new IllegalArgumentException("WRONG_PARTICIPANT");
		}
		SavedEncounter previous = store.find(event.eventId);
		if (previous != null && !previous.encounter.sameAs(event)) {
			throw new IllegalArgumentException("EVENT_CONFLICT");
		}
		if (!event.deviceB.equals(local.keyring.deviceId)) {
			throw new IllegalArgumentException("WRONG_COORDINATOR");
		}
		if (previous == null) {
			if (remote == null || !bilateral() || !rechecked || !event.recheckToken.equals(probe)
				|| elapsed.getAsLong() - probeAt > PRESENCE_MS || !queue.acquire(peerId)
				|| !event.profileB.sameAs(local) || !event.profileA.sameAs(remote)
				|| event.expiresAt <= clock.getAsLong()
				|| event.occurredAt > clock.getAsLong() + 30000 || existing() != null) {
				throw new IllegalArgumentException("PREPARE_PRECONDITION_FAILED");
			}
			previous = store.save(event, Phase.PREPARED);
			eventId = event.eventId;
			emit("PREPARED_LOCALLY", "same event/participants/time; own immutable submission/evidence saved");
		}
		sender.accept(Packet.ping(previous.phase == Phase.CONFIRMED ? Packet.DURABLE : Packet.PREPARED, event.eventId));
	}

	private void onDurability(Packet packet) {
		SavedEncounter previous = store.find(packet.token);
		if (previous == null || !previous.encounter.isContract()
			|| !previous.encounter.peerOf(local.keyring.deviceId).equals(peerId)) {
			throw new IllegalArgumentException("UNKNOWN_EVENT");
		}
		Encounter event = previous.encounter;
		eventId = event.eventId;
		if (clock.getAsLong() >= event.expiresAt) {
			emit("EVENT_EXPIRED", "late storage response ignored; original record and deadline retained");
			return;
		}
		boolean owner = event.deviceA.equals(local.keyring.deviceId);
		if (packet.type == Packet.PREPARED && owner) {
			store.save(event, Phase.COMMITTED);
			emit("COMMITTED_LOCALLY", "peer PREPARED verified; local commit persisted before sending COMMIT");
			sender.accept(Packet.ping(Packet.FINALIZE, eventId));
		} else if (packet.type == Packet.FINALIZE && !owner) {
			store.save(event, Phase.CONFIRMED);
			confirmed(previous);
			sender.accept(Packet.ping(Packet.DURABLE, eventId));
			emit("WAIT_RECEIPT", "local confirmed; STORED queued; waiting for peer receipt before feedback");
		} else if (packet.type == Packet.DURABLE && owner && previous.phase != Phase.PREPARED) {
			store.save(event, Phase.CONFIRMED);
			confirmed(previous);
			sender.accept(Packet.ping(Packet.RECEIPT, eventId));
			emit("BOTH_STORED", "authenticated peer STORED received; receipt queued");
			feedback(event);
		} else if (packet.type == Packet.RECEIPT && !owner && previous.phase == Phase.CONFIRMED) {
			emit("BOTH_STORED", "peer receipt proves peer received STORED");
			feedback(event);
		} else {
			throw new IllegalArgumentException("UNEXPECTED_EVENT_PHASE");
		}
	}

	private void confirmed(SavedEncounter previous) {
		queue.remove(peerId);
		queued = false;
		if (previous.phase != Phase.CONFIRMED) {
			emit("CONFIRMED_LOCALLY", "same event_id, user/keyring A/B, matched_at and expiry retained");
			emit("UPLOAD_PENDING", "PENDING_SERVICE_APP; server_received=false; can_delete_local_record=false");
		}
	}

	private void feedback(Encounter event) {
		boolean allowed = event.profileA.notificationEnabled && event.profileB.notificationEnabled;
		boolean nearby = elapsed.getAsLong() - lastSeen <= PRESENCE_MS && clock.getAsLong() < event.expiresAt;
		String state = !allowed ? "SKIPPED_SETTINGS" : !nearby ? "SKIPPED_DEPARTED" : "CLAIMED";
		if (store.claimFeedback(event.eventId, state)) {
			emit(allowed && nearby ? "FEEDBACK_READY" : "FEEDBACK_SKIPPED",
				"state=" + state + "; led_color=#FF9A3D; pattern_ms=500,500,500");
		} else {
			emit("FEEDBACK_SUPPRESSED", "existing_state=" + store.feedbackState(event.eventId));
		}
	}

	private void retryStored(SavedEncounter saved) {
		eventId = saved.encounter.eventId;
		if (clock.getAsLong() >= saved.encounter.expiresAt) {
			emit("EVENT_EXPIRED", "original deadline retained; no new event, alert or deletion");
			return;
		}
		boolean owner = saved.encounter.deviceA.equals(local.keyring.deviceId);
		if (!saved.encounter.peerOf(local.keyring.deviceId).equals(peerId)) {
			emit("DUPLICATE_SUPPRESSED", "same user pair already recorded with another keyring");
			return;
		}
		// Confirmed peers retain suppression across restarts; recovering the final receipt is harmless.
		if (saved.phase == Phase.CONFIRMED && !store.feedbackState(eventId).isEmpty()) {
			emit("DUPLICATE_SUPPRESSED", "confirmed encounter retained; no new event or repeated alert");
			return;
		}
		if (owner && saved.phase == Phase.PREPARED) {
			if (elapsed.getAsLong() - probeAt > PRESENCE_MS) {
				rechecked = false;
			}
			// After a restart, recover the original event through a fresh challenge with its original token.
			if (!rechecked) {
				queueIfReady();
				if (!bilateral() || !queued || !queue.acquire(peerId)) {
					sender.accept(Packet.profile(local));
					sender.accept(Packet.decision(context, Boolean.TRUE.equals(localDecision)));
					return;
				}
				probe = saved.encounter.recheckToken;
				probeAt = elapsed.getAsLong();
				sender.accept(Packet.recheck(Packet.RECHECK, probe, context));
			} else {
				sender.accept(Packet.event(Packet.PREPARE, saved.encounter));
			}
		} else {
			int type = owner ? Packet.FINALIZE : saved.phase == Phase.PREPARED ? Packet.PREPARED : Packet.DURABLE;
			sender.accept(Packet.ping(type, eventId));
		}
		emit("DURABILITY_RETRY", "phase=" + saved.phase + "; original event/submission/evidence unchanged");
		nextRetry = elapsed.getAsLong() + retryDelay();
	}

	public void retry() {
		nextRetry = 0;
		tick();
	}

	public void ping() {
		ping = UUID.randomUUID();
		sender.accept(Packet.ping(Packet.PING, ping));
	}

	public void close() {
		started = false;
		algorithms.remove(peerId);
		queue.remove(peerId);
	}

	private void emit(String code, String detail) {
		stage = code;
		observer.accept(new FlowEvent(code, peerId, eventId, detail));
	}
}

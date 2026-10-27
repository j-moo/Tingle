package com.e106.keyring.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Local FIFO evaluation, durable outbox and isolated retries. Never sends a radio packet. */
public final class IndependentMatcher {
	private final DemoProfile local;
	private final LocalMatchStore store;
	private final MatchingRule rule;
	private final LongSupplier clock;
	private final Consumer<FlowEvent> observer;
	private final Map<UUID, MatchWork> ready = new LinkedHashMap<>();
	private final Map<UUID, MatchWork> errors = new LinkedHashMap<>();
	private final Set<UUID> completed = new LinkedHashSet<>();
	private boolean draining;

	public IndependentMatcher(DemoProfile local, LocalMatchStore store, MatchingRule rule,
		LongSupplier clock, Consumer<FlowEvent> observer) {
		this.local = local;
		this.store = store;
		this.rule = rule;
		this.clock = clock;
		this.observer = observer;
	}

	public void restore() {
		for (MatchWork work : store.pendingWork()) {
			(work.error.isEmpty() ? ready : errors).put(work.id, work);
		}
		drain();
		retryDue();
	}

	public void receive(DemoProfile remote) {
		if (remote.keyring.deviceId.equals(local.keyring.deviceId) || remote.userId.equals(local.userId)) {
			return;
		}
		MatchWork work = new MatchWork(local, remote, clock.getAsLong());
		if (completed.contains(work.id) || ready.containsKey(work.id) || errors.containsKey(work.id)) {
			return;
		}
		if (ready.size() + errors.size() >= 128) {
			emit(work, "LOCAL_QUEUE_FULL", "capacity=128; not_accepted=true");
			return;
		}
		ready.put(work.id, work);
		emit(work, "LOCAL_QUEUED", "received_at_ms=" + work.receivedAt + "; intentional_delay_ms=0");
		drain();
	}

	public int errorCount() {
		return errors.size();
	}

	public List<MatchWork> failures() {
		return new ArrayList<>(errors.values());
	}

	/** Zero means no timer is needed. Normal jobs have no retry deadline. */
	public long nextRetryAt() {
		return errors.values().stream().mapToLong(value -> value.retryAt).min().orElse(0);
	}

	public void retryDue() {
		long now = clock.getAsLong();
		for (MatchWork work : new ArrayList<>(errors.values())) {
			if (work.retryAt <= now) {
				errors.remove(work.id);
				ready.put(work.id, work);
			}
		}
		drain();
	}

	public void retryFailures() {
		for (MatchWork work : errors.values()) {
			ready.put(work.id, work);
		}
		errors.clear();
		drain();
	}

	private void drain() {
		if (draining) {
			return;
		}
		draining = true;
		try {
			while (!ready.isEmpty()) {
				MatchWork work = ready.remove(ready.keySet().iterator().next());
				try {
					store.putWork(work);
					process(work);
					store.removeWork(work.id);
					completed.add(work.id);
					if (completed.size() > 512) {
						completed.remove(completed.iterator().next());
					}
				} catch (RuntimeException exception) {
					work.attempts++;
					work.retryAt = clock.getAsLong() + Math.min(30000, 1000L << Math.min(5, work.attempts - 1));
					String reason = exception.getMessage();
					work.error = reason != null && reason.matches("[A-Z0-9_]{1,64}")
						? reason : exception.getClass().getSimpleName();
					errors.put(work.id, work);
					boolean persisted = true;
					try {
						store.putWork(work);
					} catch (RuntimeException persistenceFailure) {
						persisted = false;
					}
					emit(work, "LOCAL_RETRY_WAIT", "stage=" + work.stage + "; attempts=" + work.attempts
						+ "; retry_at_ms=" + work.retryAt + "; error=" + work.error + "; durable=" + persisted);
				}
			}
		} finally {
			draining = false;
		}
	}

	private void process(MatchWork work) {
		if (work.stage == MatchWork.Stage.MATCH) {
			if (clock.getAsLong() - work.receivedAt >= work.local.opportunityMillis) {
				work.stage = MatchWork.Stage.DONE;
				emit(work, "EVENT_EXPIRED", "failed_input_expired=true; new_event=false");
				return;
			}
			SavedEncounter existing = store.completedPair(work.local.userId, work.remote.userId);
			if (existing != null) {
				work.stage = MatchWork.Stage.DONE;
				emit(work, "LOCAL_DUPLICATE", "existing_event=" + existing.encounter.eventId);
				return;
			}
			emit(work, "LOCAL_EVALUATING", "queue_wait_ms=" + (clock.getAsLong() - work.receivedAt));
			boolean allowed = eligible(work) && rule.matches(work.local, work.remote)
				&& rule.matches(work.remote, work.local);
			emit(work, "LOCAL_EVALUATED", "allowed=" + allowed + "; peer_decision_requested=false");
			if (!allowed) {
				work.stage = MatchWork.Stage.DONE;
				return;
			}
			// Retry must not extend the opportunity by moving the observation's reference time.
			work.matchedAt = work.receivedAt;
			work.stage = MatchWork.Stage.SAVE;
			store.putWork(work);
		}
		if (work.stage == MatchWork.Stage.SAVE) {
			store.save(Encounter.independent(work.eventId, work.local, work.remote, work.matchedAt),
				SavedEncounter.Phase.CONFIRMED);
			work.stage = MatchWork.Stage.FEEDBACK;
			store.putWork(work);
			emit(work, "LOCAL_SAVED", "receive_to_save_ms=" + (clock.getAsLong() - work.receivedAt)
				+ "; peer_storage_verified=false; upload_state=LOCAL_PENDING; proximity_recheck=false");
		}
		if (work.stage == MatchWork.Stage.FEEDBACK) {
			boolean allowed = work.local.notificationEnabled && work.remote.notificationEnabled;
			boolean expired = clock.getAsLong() >= work.matchedAt + work.local.opportunityMillis;
			String state = !allowed ? "SKIPPED_SETTINGS" : expired ? "SKIPPED_EXPIRED" : "CLAIMED";
			if (store.claimFeedback(work.eventId, state)) {
				emit(work, allowed && !expired ? "FEEDBACK_READY" : "FEEDBACK_SKIPPED",
					"state=" + state + "; pattern_ms=500,500,500; local_only=true");
			}
			work.stage = MatchWork.Stage.DONE;
		}
	}

	private boolean eligible(MatchWork work) {
		DemoProfile first = work.local;
		DemoProfile second = work.remote;
		return first.keyring.ready && second.keyring.ready && first.matchingEnabled && second.matchingEnabled
			&& first.policyVersion.equals(second.policyVersion) && first.ruleVersion.equals(second.ruleVersion)
			&& first.opportunityMillis == second.opportunityMillis
			&& (!first.requirePhoto || second.hasPhoto) && (!second.requirePhoto || first.hasPhoto);
	}

	private void emit(MatchWork work, String code, String detail) {
		try {
			observer.accept(new FlowEvent(code, work.remote.keyring.deviceId, work.eventId, detail));
		} catch (RuntimeException ignored) {
			// Diagnostics must not turn a completed durable operation into a second match.
		}
	}
}

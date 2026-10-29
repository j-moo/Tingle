package com.e106.tingle.chat.deletion.external;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Outcome;

/** Only matching.chat_external_cleanup_state owns this document; the original DB deletion state is never replaced. */
public record ExternalCleanupState(int schemaVersion, UUID epoch, Approval approval, Binding binding,
	Status status, int attempt, Instant leaseUntil, Instant nextAttemptAt, List<Target> targets,
	List<Evidence> history, List<String> restorationIds, String failureCode, Instant verifiedAt) {

	public ExternalCleanupState {
		if (schemaVersion != 1 || epoch == null || status == null || attempt < 0
			|| (status != Status.BLOCKED && (approval == null || binding == null))) {
			throw new IllegalArgumentException("Invalid external cleanup state");
		}
		targets = List.copyOf(targets);
		history = List.copyOf(history);
		restorationIds = List.copyOf(restorationIds);
	}

	public ExternalCleanupState progress(Status next, int nextAttempt, Instant lease, Instant retryAt,
		List<Target> results, String code, Instant verified) {
		return new ExternalCleanupState(schemaVersion, epoch, approval, binding, next, nextAttempt,
			lease, retryAt, results, history, restorationIds, code, verified);
	}

	public ExternalCleanupState invalidate(String code) {
		List<Evidence> preserved = new ArrayList<>(history);
		if (status == Status.VERIFIED) {
			preserved.add(evidence());
		}
		return new ExternalCleanupState(schemaVersion, epoch, approval, binding, Status.BLOCKED, attempt,
			null, null, targets, preserved, restorationIds, code, null);
	}

	public ExternalCleanupState restoredBy(String notificationId) {
		List<String> ids = new ArrayList<>(restorationIds);
		ids.add(notificationId);
		return new ExternalCleanupState(schemaVersion, epoch, approval, binding, status, attempt, leaseUntil,
			nextAttemptAt, targets, history, ids, "EXTERNAL_RESTORED", verifiedAt);
	}

	public Evidence evidence() {
		return new Evidence(epoch, binding, status, targets, failureCode, verifiedAt);
	}

	public enum Status { RUNNING, RETRY, BLOCKED, VERIFIED }

	/** The first approval and first DB deletion evidence survive retries, restoration and re-verification. */
	public record Approval(Context context, UUID dbGeneration, Inventory inventory, Instant firstDbDeletedAt) {
	}

	public record Binding(Context context, UUID dbGeneration, int dbAttempt, String inventoryHash,
		Instant firstDbDeletedAt) {
	}

	public record Target(Reference reference, String scopeHash, Outcome outcome, String receipt,
		String failureCode, Instant checkedAt, UUID dispatchId) {
		public boolean confirmed() {
			return outcome != null && outcome.confirmed();
		}
	}

	/** An immutable past epoch, not proof that copies remain absent in the current epoch. */
	public record Evidence(UUID epoch, Binding binding, Status status, List<Target> targets,
		String failureCode, Instant verifiedAt) {
		public Evidence {
			targets = List.copyOf(targets);
		}
	}
}

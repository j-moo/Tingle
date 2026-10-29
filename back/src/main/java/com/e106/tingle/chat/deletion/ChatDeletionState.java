package com.e106.tingle.chat.deletion;

import java.time.Instant;
import java.util.UUID;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;

/** Stored only in matching.chat_deletion_state; survives physical deletion of the chat and its children. */
public record ChatDeletionState(int schemaVersion, UUID generation, Context context, Status status, int attempt,
	Instant leaseUntil, Instant nextAttemptAt, Inventory inventory, String failureCode, Instant dbDeletedAt) {
	public enum Status {
		RUNNING, WAITING_RETENTION, BLOCKED_REFERENCES, BLOCKED_SOURCE_CHANGED, BLOCKED_GENERATION, RETRY, DB_DELETED
	}

	public ChatDeletionState {
		if (schemaVersion != 1 || generation == null || context == null || status == null || attempt < 0
			|| inventory == null || context.matchingId() <= 0 || context.chatId() <= 0 || context.createdAt() == null
			|| context.closedAt() == null || context.dueAt() == null || context.sourceFingerprint() == null) {
			throw new IllegalArgumentException("Invalid chat deletion state");
		}
	}

	public ChatDeletionState transition(Status next, Instant retryAt, String code) {
		return new ChatDeletionState(schemaVersion, generation, context, next, attempt, null, retryAt,
			inventory, code, dbDeletedAt);
	}
}

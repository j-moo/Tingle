package com.e106.tingle.user;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;

import lombok.RequiredArgsConstructor;

/** Metadata redaction revokes deletion approval without losing known external copies or rewriting the journal. */
@Service
@RequiredArgsConstructor
public class MemberWithdrawalDeletionInvalidator {
	private final ChatDeletionRepository deletions;

	@Transactional(propagation = Propagation.MANDATORY)
	public void invalidateChangedMetadata(long matchingId, Instant now) {
		ChatDeletionState previous = deletions.state(matchingId).orElse(null);
		if (previous == null) {
			return;
		}
		Context actual = deletions.lockContext(matchingId, now, previous.context().dueAt()).orElse(null);
		if (actual == null || previous.context().sourceFingerprint().equals(actual.sourceFingerprint())) {
			return;
		}
		if (actual.chatId() != previous.context().chatId()
			|| !actual.createdAt().equals(previous.context().createdAt())
			|| !actual.closedAt().equals(previous.context().closedAt())) {
			deletions.save(matchingId, previous.transition(Status.BLOCKED_GENERATION, null, "GENERATION_CHANGED"));
			return;
		}
		Inventory unapproved = new Inventory(false, false, null, previous.inventory().references());
		deletions.save(matchingId, new ChatDeletionState(1, previous.generation(), actual,
			Status.BLOCKED_REFERENCES, Math.incrementExact(previous.attempt()), null, now, unapproved,
			"WITHDRAWAL_METADATA_REDACTED", previous.dbDeletedAt()));
		// A new approval must include every old reference. Existing PREPARED/old-fingerprint journal entries
		// deliberately continue to block restore until privileged reconciliation; they are never erased here.
	}
}

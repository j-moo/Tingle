package com.e106.tingle.chat.deletion.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;

/**
 * Candidate consumer boundary for an independently approved backup inventory.
 * Not a Spring bean or a provider: the default unresolved source remains in effect.
 */
public final class BackupInventoryValidator {

	private static final int MAX_REFERENCES = 1000;

	public Inventory validate(Evidence evidence, Approval approval, Context expected, Instant now) {
		if (evidence == null || approval == null || expected == null || now == null
			|| !validApproval(approval) || evidence.schemaVersion() != 1
			|| !approval.namespace().equals(evidence.namespace())
			|| !approval.provider().equals(evidence.provider())
			|| !approval.contractVersion().equals(evidence.contractVersion())
			|| !approval.approvalId().equals(evidence.approvalId())
			|| !approval.restoreGeneration().equals(evidence.restoreGeneration())
			|| !expected.equals(evidence.context()) || !validContext(expected)
			|| !evidence.complete() || evidence.inspectedAt() == null
			|| evidence.inspectedAt().isAfter(now)
			|| evidence.inspectedAt().isBefore(now.minus(approval.maxAge()))
			|| evidence.inspectedAt().isBefore(expected.closedAt())
			|| evidence.references() == null || evidence.references().size() > MAX_REFERENCES) {
			return Inventory.unresolved();
		}
		List<String> references = evidence.references();
		if (references.isEmpty() && !evidence.absenceConfirmed()
			|| !references.isEmpty() && evidence.absenceConfirmed()
			|| new HashSet<>(references).size() != references.size()) {
			return Inventory.unresolved();
		}
		try {
			List<Reference> mapped = references.stream()
				.map(id -> new Reference(approval.provider(), approval.contractVersion(), id)).toList();
			return new Inventory(true, evidence.absenceConfirmed(), approval.approvalId(), mapped);
		} catch (IllegalArgumentException exception) {
			return Inventory.unresolved();
		}
	}

	private boolean validApproval(Approval approval) {
		return identifier(approval.namespace(), 100) && identifier(approval.provider(), 100)
			&& identifier(approval.contractVersion(), 100) && identifier(approval.approvalId(), 120)
			&& identifier(approval.restoreGeneration(), 100) && approval.maxAge() != null
			&& !approval.maxAge().isNegative() && !approval.maxAge().isZero()
			&& approval.maxAge().compareTo(Duration.ofDays(1)) <= 0;
	}

	private boolean validContext(Context context) {
		return context.matchingId() > 0 && context.chatId() > 0 && context.createdAt() != null
			&& context.closedAt() != null && !context.closedAt().isBefore(context.createdAt())
			&& context.dueAt() != null && !context.dueAt().isBefore(context.closedAt())
			&& context.sourceFingerprint() != null && context.sourceFingerprint().matches("[0-9a-f]{64}");
	}

	private boolean identifier(String value, int maximum) {
		return value != null && value.length() <= maximum && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
	}

	/** Set by the independent approving consumer, never copied from an incoming evidence document. */
	public record Approval(String namespace, String provider, String contractVersion, String approvalId,
		String restoreGeneration, Duration maxAge) {
	}

	/** The source authenticity and inventory completeness still require an approved supplier contract. */
	public record Evidence(int schemaVersion, String namespace, String provider, String contractVersion,
		String approvalId, String restoreGeneration, Context context, Instant inspectedAt, boolean complete,
		boolean absenceConfirmed, List<String> references) {
		public Evidence {
			references = references == null ? null : List.copyOf(references);
		}
	}
}

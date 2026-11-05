package com.e106.tingle.chat.deletion.external;

import java.util.UUID;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Binding;

/** No production adapter is registered until an actual provider and its deletion contract are approved. */
public interface ExternalCopyDeletionPort {

	String provider();

	String contractVersion();

	/**
	 * Called outside database transactions. Providers must use scopeHash as the idempotency key, including retries
	 * after an uncertain response. A new epoch always performs a fresh absence check rather than trusting old evidence.
	 */
	Result deleteAndVerify(Request request);

	record Request(long matchingId, UUID epoch, int attempt, UUID dispatchId, Binding binding, String approvalId,
		Reference reference, String scopeHash) {
	}

	/** Untrusted provider response: the service validates scope and opaque evidence before persisting anything. */
	record Result(Outcome outcome, Reference reference, String scopeHash, String receipt, String failureCode) {
	}

	enum Outcome {
		ABSENT_CONFIRMED, DELETE_CONFIRMED, RETRYABLE_FAILED, BLOCKED_UNSUPPORTED, BLOCKED_UNRESOLVED;

		public boolean confirmed() {
			return this == ABSENT_CONFIRMED || this == DELETE_CONFIRMED;
		}
	}
}

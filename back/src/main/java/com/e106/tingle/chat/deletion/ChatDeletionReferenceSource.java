package com.e106.tingle.chat.deletion;

import java.time.Instant;
import java.util.List;

/**
 * Privileged, metadata-only inventory of every approved external copy for this exact chat generation.
 * Implementations must not call a network service or delete anything: this runs inside the database transaction.
 * A missing source or an empty list without an explicit complete-inventory attestation never authorizes deletion.
 */
public interface ChatDeletionReferenceSource {
	Inventory inspect(Context context);

	record Context(long matchingId, long chatId, Instant createdAt, Instant closedAt, Instant dueAt,
		String sourceFingerprint) {
	}

	/** Opaque, contract-approved identifiers only. Never put message bodies, credentials or signed URLs here. */
	record Reference(String provider, String contractVersion, String opaqueId) {
		public Reference {
			if (!identifier(provider, 100) || !identifier(contractVersion, 100) || !identifier(opaqueId, 512)) {
				throw new IllegalArgumentException("Invalid approved external reference");
			}
		}

		private static boolean identifier(String value, int maximum) {
			return value != null && value.length() <= maximum && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")
				&& !value.contains("..") && !value.contains("://");
		}
	}

	record Inventory(boolean complete, boolean absenceConfirmed, String approvalId, List<Reference> references) {
		public Inventory {
			references = references == null ? List.of() : List.copyOf(references);
			if (approvalId != null && !approvalId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
				throw new IllegalArgumentException("Invalid inventory approval reference");
			}
		}

		public static Inventory unresolved() {
			return new Inventory(false, false, null, List.of());
		}

		public boolean authorizesDeletion() {
			return complete && approvalId != null && approvalId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
				&& (!references.isEmpty() || absenceConfirmed);
		}
	}
}

package com.e106.keyring.core;

import java.util.UUID;

public interface ContractStore extends RecordStore {
	default SavedEncounter findContractPair(UUID firstUser, UUID secondUser) {
		return list().stream().filter(value -> value.encounter.isContract()
			&& ((value.encounter.profileA.userId.equals(firstUser)
			&& value.encounter.profileB.userId.equals(secondUser))
			|| (value.encounter.profileA.userId.equals(secondUser)
			&& value.encounter.profileB.userId.equals(firstUser))))
			.findFirst().orElse(null);
	}

	/** Persist before actuation. A crash may suppress an alert but must not repeat an already claimed alert. */
	boolean claimFeedback(UUID eventId, String state);

	void finishFeedback(UUID eventId, String state);

	String feedbackState(UUID eventId);
}

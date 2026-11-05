package com.e106.keyring.core;

import java.util.List;
import java.util.UUID;

public interface RecordStore {
	SavedEncounter find(UUID eventId);

	SavedEncounter findPair(UUID first, UUID second);

	default SavedEncounter findPendingPair(UUID first, UUID second) {
		return list().stream().filter(value -> value.phase != SavedEncounter.Phase.CONFIRMED
			&& ((value.encounter.deviceA.equals(first) && value.encounter.deviceB.equals(second))
			|| (value.encounter.deviceA.equals(second) && value.encounter.deviceB.equals(first))))
			.findFirst().orElse(null);
	}

	/** Return only after a durable transaction; preserve payload and submission ID on retries. */
	SavedEncounter save(Encounter encounter, SavedEncounter.Phase phase);

	List<SavedEncounter> list();
}

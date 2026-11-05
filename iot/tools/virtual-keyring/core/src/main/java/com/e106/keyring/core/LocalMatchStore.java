package com.e106.keyring.core;

import java.util.List;
import java.util.UUID;

public interface LocalMatchStore extends ContractStore {
	List<MatchWork> pendingWork();

	void putWork(MatchWork work);

	void removeWork(UUID id);

	default SavedEncounter completedPair(UUID local, UUID remote) {
		return list().stream().filter(value -> value.phase == SavedEncounter.Phase.CONFIRMED
			&& value.encounter.hasProfiles()
			&& ((value.encounter.profileA.userId.equals(local) && value.encounter.profileB.userId.equals(remote))
			|| (value.encounter.profileA.userId.equals(remote) && value.encounter.profileB.userId.equals(local))))
			.findFirst().orElse(null);
	}
}

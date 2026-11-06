package com.e106.keyring.core;

import java.util.UUID;

public final class SavedEncounter {
	public enum Phase {
		PREPARED, COMMITTED, CONFIRMED
	}

	public final Encounter encounter;
	public final UUID submissionId;
	public final Phase phase;
	public final LocalEvidence evidence;

	public SavedEncounter(Encounter encounter, UUID submissionId, Phase phase) {
		this(encounter, submissionId, phase, null);
	}

	public SavedEncounter(Encounter encounter, UUID submissionId, Phase phase, LocalEvidence evidence) {
		this.encounter = encounter;
		this.submissionId = submissionId;
		this.phase = phase;
		this.evidence = evidence;
	}

	public static Phase advance(Phase oldPhase, Phase nextPhase) {
		if (oldPhase == Phase.CONFIRMED || nextPhase == Phase.CONFIRMED) {
			return Phase.CONFIRMED;
		}
		if (oldPhase == Phase.COMMITTED || nextPhase == Phase.COMMITTED) {
			return Phase.COMMITTED;
		}
		return Phase.PREPARED;
	}
}

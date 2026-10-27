package com.e106.keyring.core;

import java.util.Objects;
import java.util.UUID;

/** Immutable LAB-1 evidence. This is deliberately not a production server submission. */
public final class Encounter {
	public final UUID eventId;
	public final UUID deviceA;
	public final UUID deviceB;
	public final UUID snapshotA;
	public final UUID snapshotB;
	public final long versionA;
	public final long versionB;
	public final long occurredAt;
	public final DemoProfile profileA;
	public final DemoProfile profileB;
	public final UUID recheckToken;
	public final long expiresAt;
	public final boolean independent;

	public Encounter(UUID eventId, Peer first, Peer second, long occurredAt) {
		this(eventId, first, second, occurredAt, null, null, null);
	}

	public Encounter(UUID eventId, DemoProfile first, DemoProfile second, long occurredAt, UUID recheckToken) {
		this(eventId, first.keyring, second.keyring, occurredAt, first, second, recheckToken);
		if (first.userId.toString().compareTo(second.userId.toString()) >= 0 || recheckToken == null) {
			throw new IllegalArgumentException("INVALID_USER_ORDER");
		}
	}

	private Encounter(UUID eventId, Peer first, Peer second, long occurredAt,
		DemoProfile profileA, DemoProfile profileB, UUID recheckToken) {
		this(eventId, first, second, occurredAt, profileA, profileB, recheckToken, false);
	}

	private Encounter(UUID eventId, Peer first, Peer second, long occurredAt,
		DemoProfile profileA, DemoProfile profileB, UUID recheckToken, boolean independent) {
		this.eventId = Objects.requireNonNull(eventId);
		this.deviceA = first.deviceId;
		this.deviceB = second.deviceId;
		this.snapshotA = first.snapshotId;
		this.snapshotB = second.snapshotId;
		this.versionA = first.settingsVersion;
		this.versionB = second.settingsVersion;
		if (deviceA.equals(deviceB) || occurredAt <= 0 || versionA <= 0 || versionB <= 0) {
			throw new IllegalArgumentException("INVALID_ENCOUNTER");
		}
		this.occurredAt = occurredAt;
		this.profileA = profileA;
		this.profileB = profileB;
		this.recheckToken = recheckToken;
		this.independent = independent;
		expiresAt = profileA == null ? 0 : Math.addExact(occurredAt, profileA.opportunityMillis);
	}

	public boolean isContract() {
		return profileA != null && !independent;
	}

	public boolean hasProfiles() {
		return profileA != null;
	}

	/** Local observation only: no shared clock, remote decision or remote storage assertion. */
	public static Encounter independent(UUID id, DemoProfile own, DemoProfile peer, long matchedAt) {
		if (own.userId.equals(peer.userId)) {
			throw new IllegalArgumentException("SELF_MATCH");
		}
		DemoProfile first = own.userId.toString().compareTo(peer.userId.toString()) < 0 ? own : peer;
		DemoProfile second = first == own ? peer : own;
		return new Encounter(id, first.keyring, second.keyring, matchedAt, first, second, null, true);
	}

	public String pairKey() {
		String first = deviceA.toString();
		String second = deviceB.toString();
		return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first;
	}

	public UUID peerOf(UUID localId) {
		if (deviceA.equals(localId)) {
			return deviceB;
		}
		if (deviceB.equals(localId)) {
			return deviceA;
		}
		throw new IllegalArgumentException("WRONG_PARTICIPANT");
	}

	public boolean sameAs(Encounter other) {
		return other != null && eventId.equals(other.eventId) && deviceA.equals(other.deviceA)
			&& deviceB.equals(other.deviceB) && snapshotA.equals(other.snapshotA)
			&& snapshotB.equals(other.snapshotB) && versionA == other.versionA
			&& versionB == other.versionB && occurredAt == other.occurredAt
			&& independent == other.independent && hasProfiles() == other.hasProfiles() && (!hasProfiles()
			|| (profileA.sameAs(other.profileA) && profileB.sameAs(other.profileB)
			&& Objects.equals(recheckToken, other.recheckToken)));
	}
}

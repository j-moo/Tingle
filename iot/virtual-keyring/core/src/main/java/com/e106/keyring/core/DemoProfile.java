package com.e106.keyring.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Synthetic, persisted input. These values are never represented as server-issued settings. */
public final class DemoProfile {
	public static final String POLICY = "lab-policy-1";
	public static final String RULE = "lab-always-match-1";
	public static final long OPPORTUNITY_MS = 7L * 24 * 60 * 60 * 1000;
	public final UUID userId;
	public final Peer keyring;
	public final boolean matchingEnabled;
	public final boolean notificationEnabled;
	public final boolean hasPhoto;
	public final boolean requirePhoto;
	public final int age;
	public final String policyVersion;
	public final String ruleVersion;
	public final long opportunityMillis;
	private final byte[] personality;
	private final byte[] desired;

	public DemoProfile(UUID userId, Peer keyring, boolean matching, boolean notification,
		boolean photo, boolean requirePhoto, int age, byte[] personality, byte[] desired) {
		this(userId, keyring, matching, notification, photo, requirePhoto, age, personality, desired,
			POLICY, RULE, OPPORTUNITY_MS);
	}

	public DemoProfile(UUID userId, Peer keyring, boolean matching, boolean notification,
		boolean photo, boolean requirePhoto, int age, byte[] personality, byte[] desired,
		String policyVersion, String ruleVersion, long opportunityMillis) {
		if (userId == null || keyring == null || age < 19 || age > 120
			|| personality.length != 5 || desired.length != 5 || opportunityMillis <= 0
			|| opportunityMillis > 365L * 24 * 60 * 60 * 1000
			|| !policyVersion.matches("[a-zA-Z0-9.-]{1,64}") || !ruleVersion.matches("[a-zA-Z0-9.-]{1,64}")) {
			throw new IllegalArgumentException("INVALID_DEMO_PROFILE");
		}
		for (byte[] vector : new byte[][] {personality, desired}) {
			for (byte value : vector) {
				if (value < 0 || value > 100) {
					throw new IllegalArgumentException("INVALID_DEMO_TRAIT");
				}
			}
		}
		this.userId = userId;
		this.keyring = keyring;
		matchingEnabled = matching;
		notificationEnabled = notification;
		hasPhoto = photo;
		this.requirePhoto = requirePhoto;
		this.age = age;
		this.policyVersion = policyVersion;
		this.ruleVersion = ruleVersion;
		this.opportunityMillis = opportunityMillis;
		this.personality = personality.clone();
		this.desired = desired.clone();
	}

	public static DemoProfile fixture(Peer keyring) {
		UUID user = UUID.nameUUIDFromBytes(("lab-user/" + keyring.deviceId).getBytes(StandardCharsets.UTF_8));
		byte[] traits = new byte[5];
		for (int index = 0; index < traits.length; index++) {
			traits[index] = (byte)(30 + Math.floorMod(user.hashCode() + index * 17, 61));
		}
		return new DemoProfile(user, keyring, true, true, true, false, 25, traits,
			new byte[] {50, 50, 50, 50, 50});
	}

	public byte[] personality() {
		return personality.clone();
	}

	public byte[] desired() {
		return desired.clone();
	}

	public boolean sameAs(DemoProfile other) {
		return other != null && Arrays.equals(Packet.profile(this).encode(), Packet.profile(other).encode());
	}

	public static UUID context(DemoProfile first, DemoProfile second) {
		String firstKey = first.userId + "/" + first.keyring.deviceId;
		String secondKey = second.userId + "/" + second.keyring.deviceId;
		DemoProfile firstProfile = firstKey.compareTo(secondKey) < 0 ? first : second;
		DemoProfile secondProfile = firstProfile == first ? second : first;
		byte[] left = Packet.profile(firstProfile).encode();
		byte[] right = Packet.profile(secondProfile).encode();
		byte[] both = Arrays.copyOf(left, left.length + right.length);
		System.arraycopy(right, 0, both, left.length, right.length);
		return UUID.nameUUIDFromBytes(both);
	}

	void write(DataOutputStream output) throws IOException {
		Packet.writeId(output, userId);
		Packet.writePeer(output, keyring);
		output.writeByte((matchingEnabled ? 1 : 0) | (notificationEnabled ? 2 : 0)
			| (hasPhoto ? 4 : 0) | (requirePhoto ? 8 : 0));
		output.writeByte(age);
		output.write(personality);
		output.write(desired);
		output.writeUTF(policyVersion);
		output.writeUTF(ruleVersion);
		output.writeLong(opportunityMillis);
	}

	static DemoProfile read(DataInputStream input) throws IOException {
		UUID user = Packet.readId(input);
		Peer peer = Packet.readPeer(input);
		int flags = input.readUnsignedByte();
		int age = input.readUnsignedByte();
		byte[] traits = new byte[5];
		byte[] desired = new byte[5];
		input.readFully(traits);
		input.readFully(desired);
		if (flags > 15) {
			throw new IllegalArgumentException("INCOMPATIBLE_DEMO_INPUT");
		}
		return new DemoProfile(user, peer, (flags & 1) != 0, (flags & 2) != 0,
			(flags & 4) != 0, (flags & 8) != 0, age, traits, desired,
			input.readUTF(), input.readUTF(), input.readLong());
	}
}

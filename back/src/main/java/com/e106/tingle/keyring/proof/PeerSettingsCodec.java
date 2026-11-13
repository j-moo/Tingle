package com.e106.tingle.keyring.proof;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure codec for the pinned IoT TSV3 candidate; this class does not approve policy or issue signatures. */
public final class PeerSettingsCodec {

	public static final String FORMAT = "tingle-keyring-settings-v3";
	private static final int BASE_BYTES = 487;
	private static final int MAX_RESTRICTIONS = 60;
	private static final long UINT32_MAX = 0xffffffffL;
	private static final long MAX_OPPORTUNITY_SECONDS = 365L * 86400;
	private static final long POLICY = 0x52504331L;
	private static final long RULE = 0x10001101L;
	private static final byte[] DOMAIN = "TINGLE-PEER-GRANT-V3\n".getBytes(StandardCharsets.US_ASCII);
	private static final int[] TAGS = {1, 0x10, 0x11, 0x20, 0x21, 0x30, 0x31, 0x32, 0x40, 0x41};
	private static final int[] LENGTHS = {46, 9, 39, 5, 39, 3, 2, 2, 16, 1};

	private PeerSettingsCodec() {
	}

	/** Returns the binary domain plus the first197 grant bytes, excluding the64 signature bytes. */
	public static byte[] grantTranscript(GrantClaims claims, byte[] profile) {
		byte[] body = grantBody(claims, profile);
		return ByteBuffer.allocate(DOMAIN.length + body.length).put(DOMAIN).put(body).array();
	}

	/** Signature verification belongs to the signer/issuer boundary; all policy inputs must be explicit. */
	public static byte[] encode(GrantClaims claims, byte[] profile, byte[] grantSignature,
		int presenceGapSeconds, boolean restrictionsComplete, boolean deviceAllowed, List<Restriction> restrictions) {
		byte[] body = grantBody(claims, profile);
		require(grantSignature != null && grantSignature.length == 64);
		require(presenceGapSeconds >= 1 && presenceGapSeconds <= 300 && restrictions != null
			&& restrictions.size() <= MAX_RESTRICTIONS);
		ByteBuffer out = little(BASE_BYTES + restrictions.size() * 25);
		out.put("TSV3".getBytes(StandardCharsets.US_ASCII)).put(profile).put(body).put(grantSignature);
		out.putInt((int)claims.policyVersion()).putInt((int)claims.matchingRuleVersion());
		out.putLong(seconds(claims.validUntil())).putShort((short)restrictions.size());
		out.putShort((short)presenceGapSeconds).put((byte)(restrictionsComplete ? 1 : 0));
		out.put((byte)(deviceAllowed ? 1 : 0)).putInt((int)claims.opportunitySeconds());
		Set<String> seen = new HashSet<>();
		for (Restriction restriction : restrictions) {
			require(restriction != null);
			byte[] owner = restriction.ownerRef();
			require(owner != null && owner.length == 16 && nonzero(owner) && seen.add(HexFormat.of().formatHex(owner)));
			require((restriction.kind() == 1 && restriction.expiresAtEpochSeconds() > 0)
				|| (restriction.kind() == 2 && restriction.expiresAtEpochSeconds() == 0));
			out.put(owner).put((byte)restriction.kind()).putLong(restriction.expiresAtEpochSeconds());
		}
		return out.array();
	}

	/** Checks framing and internal bindings, not cryptographic signatures or policy approval. */
	public static boolean validPayloadFraming(byte[] payload) {
		if (payload == null || payload.length < BASE_BYTES || !Arrays.equals(Arrays.copyOf(payload, 4),
			"TSV3".getBytes(StandardCharsets.US_ASCII))) {
			return false;
		}
		try {
			ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
			byte[] profile = Arrays.copyOfRange(payload, 4, 200);
			validateProfile(profile);
			int count = Short.toUnsignedInt(buffer.getShort(477));
			long opportunity = Integer.toUnsignedLong(buffer.getInt(483));
			long validUntil = buffer.getLong(469);
			long issuedAt = buffer.getLong(325);
			int presence = Short.toUnsignedInt(buffer.getShort(479));
			if (count > MAX_RESTRICTIONS || payload.length != BASE_BYTES + count * 25 || payload[200] != 4
				|| buffer.getLong(209) <= 0 || buffer.getInt(217) == 0 || validUntil <= 0 || issuedAt <= 0
				|| issuedAt >= validUntil || opportunity < 1 || opportunity > MAX_OPPORTUNITY_SECONDS
				|| buffer.getInt(225) != (int)opportunity || presence < 1 || presence > 300
				|| payload[481] < 0 || payload[481] > 1 || payload[482] < 0 || payload[482] > 1
				|| buffer.getInt(461) != (int)POLICY || buffer.getInt(465) != (int)RULE
				|| buffer.getInt(309) != buffer.getInt(461) || buffer.getInt(313) != buffer.getInt(465)
				|| buffer.getLong(317) != validUntil || !nonzero(Arrays.copyOfRange(payload, 293, 309))
				|| !Arrays.equals(hash(profile), Arrays.copyOfRange(payload, 333, 365))) {
				return false;
			}
			Set<String> seen = new HashSet<>();
			for (int index = 0; index < count; index++) {
				int offset = BASE_BYTES + index * 25;
				byte[] owner = Arrays.copyOfRange(payload, offset, offset + 16);
				int kind = payload[offset + 16];
				long expiry = buffer.getLong(offset + 17);
				if (!nonzero(owner) || !seen.add(HexFormat.of().formatHex(owner))
					|| !((kind == 1 && expiry > 0) || (kind == 2 && expiry == 0))) {
					return false;
				}
			}
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static byte[] grantBody(GrantClaims claims, byte[] profile) {
		require(claims != null);
		validateProfile(profile);
		require(claims.hardwareRef() != null && claims.hardwareRef().length == 8 && nonzero(claims.hardwareRef()));
		require(claims.deviceId() > 0 && claims.settingsVersion() > 0 && claims.settingsVersion() <= UINT32_MAX
			&& claims.ownershipEpoch() >= 0 && claims.ownershipEpoch() <= UINT32_MAX);
		require(claims.opportunitySeconds() > 0 && claims.opportunitySeconds() <= MAX_OPPORTUNITY_SECONDS);
		require(claims.ownerGeneration() != null && claims.syncRunId() != null);
		require(claims.nonce() != null && claims.nonce().length == 32 && claims.ownerRef() != null
			&& claims.ownerRef().length == 16 && nonzero(claims.ownerRef()));
		require(claims.devicePublicKey() != null && claims.devicePublicKey().length == 32);
		require(claims.policyVersion() == POLICY && claims.matchingRuleVersion() == RULE);
		long issuedAt = seconds(claims.issuedAt());
		long validUntil = seconds(claims.validUntil());
		require(validUntil > issuedAt);
		ByteBuffer out = little(197);
		out.put((byte)4).put(claims.hardwareRef()).putLong(claims.deviceId()).putInt((int)claims.settingsVersion());
		out.putInt((int)claims.ownershipEpoch()).putInt((int)claims.opportunitySeconds());
		putUuid(out, claims.ownerGeneration());
		putUuid(out, claims.syncRunId());
		out.put(claims.nonce()).put(claims.ownerRef()).putInt((int)claims.policyVersion())
			.putInt((int)claims.matchingRuleVersion());
		out.putLong(validUntil).putLong(issuedAt).put(hash(profile)).put(claims.devicePublicKey());
		return out.array();
	}

	private static void validateProfile(byte[] profile) {
		require(profile != null && profile.length == 196 && profile[0] == 'T' && profile[1] == 'P'
			&& profile[2] == 'S' && profile[3] == '1');
		int offset = 4;
		for (int index = 0; index < TAGS.length; index++) {
			require(Byte.toUnsignedInt(profile[offset]) == TAGS[index]
				&& Byte.toUnsignedInt(profile[offset + 1]) == LENGTHS[index] && profile[offset + 2] == 0);
			offset += 3 + LENGTHS[index];
		}
		require(offset == profile.length);
	}

	private static void putUuid(ByteBuffer out, UUID value) {
		ByteBuffer bytes = ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
			.putLong(value.getLeastSignificantBits());
		out.put(bytes.array());
	}

	private static long seconds(Instant value) {
		require(value != null && value.getEpochSecond() > 0 && value.getNano() == 0);
		return value.getEpochSecond();
	}

	private static byte[] hash(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static ByteBuffer little(int length) {
		return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static boolean nonzero(byte[] bytes) {
		for (byte value : bytes) {
			if (value != 0) {
				return true;
			}
		}
		return false;
	}

	private static void require(boolean condition) {
		if (!condition) {
			throw new IllegalArgumentException("Invalid TSV3 candidate input");
		}
	}

	private static byte[] copy(byte[] value) {
		return value == null ? null : value.clone();
	}

	public record GrantClaims(byte[] hardwareRef, long deviceId, long settingsVersion, long ownershipEpoch,
		long opportunitySeconds, UUID ownerGeneration, UUID syncRunId, byte[] nonce, byte[] ownerRef,
		long policyVersion, long matchingRuleVersion, Instant validUntil, Instant issuedAt, byte[] devicePublicKey) {
		public GrantClaims {
			hardwareRef = copy(hardwareRef);
			nonce = copy(nonce);
			ownerRef = copy(ownerRef);
			devicePublicKey = copy(devicePublicKey);
		}

		@Override
		public byte[] hardwareRef() {
			return copy(hardwareRef);
		}

		@Override
		public byte[] nonce() {
			return copy(nonce);
		}

		@Override
		public byte[] ownerRef() {
			return copy(ownerRef);
		}

		@Override
		public byte[] devicePublicKey() {
			return copy(devicePublicKey);
		}
	}

	public record Restriction(byte[] ownerRef, int kind, long expiresAtEpochSeconds) {
		public Restriction {
			ownerRef = copy(ownerRef);
		}

		@Override
		public byte[] ownerRef() {
			return copy(ownerRef);
		}
	}
}

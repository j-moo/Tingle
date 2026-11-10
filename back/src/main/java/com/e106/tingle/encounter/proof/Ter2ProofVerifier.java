package com.e106.tingle.encounter.proof;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/** Local cryptographic decoder. Historical ownership, approved release and bilateral intake are separate checks. */
public final class Ter2ProofVerifier {

	/**
	 * 서버가 받는 키링 제품 정책·매칭 규칙 ID(펌웨어 KR_MATCH_POLICY·KR_MATCH_RULE). 규칙 문서 검증도 이 값과 대조한다.
	 *
	 * <p>[매칭 규칙·제품 정책 버전을 올릴 때 함께 바꿀 곳] 한 곳만 바꾸면 업로드가 거절되거나 제안이 안 생긴다.
	 * <ol>
	 * <li>펌웨어: iot/include/matching.h 의 KR_MATCH_POLICY·KR_MATCH_RULE(숫자 ID),
	 *     iot/src/product.c 의 "tingle-policy-v2"·"r2-c-20261106"(기록에 담는 이름)</li>
	 * <li>앱: front/app/shared/.../keyring/data/KeyringEncounterProtocol.kt 의 버전 검사</li>
	 * <li>서버: 여기 POLICY·RULE, Ter2ReceiptService.labels 의 버전 이름</li>
	 * <li>서버 설정(/srv/tingle/.env): KEYRING_PEER_POLICY_VERSION·KEYRING_PEER_RULE_VERSION(숫자 ID, 10진수)</li>
	 * <li>규칙 문서: 새 버전 이름으로 MATCHING·PRODUCT_POLICY 를 /api/admin/v1 에 등록 → 검증 → 승인(다른 운영자) → 게시,
	 *     그 조합 ID·해시를 KEYRING_PEER_BUSINESS_RULE_RELEASE_ID·_RELEASE_HASH 에 넣기. 게시 시각 이전 만남은 제안이 안 된다.</li>
	 * </ol>
	 */
	public static final long POLICY = 0x52504331L;
	public static final long RULE = 0x10001101L;
	private static final int EVIDENCE_BYTES = 795;
	private static final int MAX_WIRE_BYTES = 52 + 230 + 400;
	private static final byte[] SPKI = HexFormat.of().parseHex("302a300506032b6570032100");

	public VerifiedEncounter verify(byte[] envelope, String expectedHash, byte[] serverKey,
		byte[] registeredSenderKey, byte[] registeredPeerKey) {
		return verify(envelope, expectedHash, serverKey,
			identity -> identity.sender() ? registeredSenderKey : registeredPeerKey);
	}

	public VerifiedEncounter verify(byte[] envelope, String expectedHash, byte[] serverKey,
		CredentialKeyResolver keys) {
		require(envelope != null && envelope.length >= 24 + 52 + 230 + EVIDENCE_BYTES + 64
			&& envelope.length <= 24 + MAX_WIRE_BYTES + EVIDENCE_BYTES + 64);
		byte[] raw = envelope.clone();
		require(expectedHash != null && expectedHash.matches("[0-9a-f]{64}")
			&& expectedHash.equals(hex(hash(raw))));
		require(serverKey != null && serverKey.length == 32 && keys != null);
		if (!magic(raw, 0, "TER2")) {
			throw unresolved();
		}
		int wireLength = u16(raw, 4);
		require(u16(raw, 6) == EVIDENCE_BYTES && wireLength >= 282 && wireLength <= MAX_WIRE_BYTES
			&& raw.length == 24 + wireLength + EVIDENCE_BYTES + 64);
		byte[] wire = slice(raw, 24, wireLength);
		byte[] evidence = slice(raw, 24 + wireLength, EVIDENCE_BYTES);
		require(magic(evidence, 0, "KEV2") && wire[0] == 1 && wire[2] == 7 && u32(wire, 4) > 0);
		Kind kind = switch (wire[1]) {
			case 2 -> Kind.CONFIRMED;
			case 3 -> Kind.REVIEW;
			default -> throw unresolved();
		};
		require(kind == Kind.REVIEW || wire[3] == 0);
		require(evidence[794] == 0 || evidence[794] == 1);
		require(kind == Kind.REVIEW || evidence[794] == 1);
		int ownLength = u16(wire, 48);
		int peerLength = u16(wire, 50);
		require(ownLength == 230 && peerLength <= 400 && wireLength == 52 + ownLength + peerLength);
		long occurred = positive(wire, 32);
		long expires = positive(wire, 40);
		require(expires > occurred && occurred <= Long.MAX_VALUE - 300
			&& expires <= Instant.MAX.getEpochSecond());
		byte[] ownProfileWire = slice(wire, 52, ownLength);
		byte[] peerPacket = slice(wire, 52 + ownLength, peerLength);
		byte[] ownProfile = slice(ownProfileWire, 34, 196);
		require(u16(ownProfileWire, 32) == 196 && magic(ownProfile, 0, "TPS1"));
		require(u32(evidence, 4) == u32(wire, 4) && le(evidence).getLong(8) == le(wire).getLong(24)
			&& Arrays.equals(hash(ownProfile), slice(evidence, 16, 32))
			&& Arrays.equals(hash(peerPacket), slice(evidence, 48, 32)));
		byte[] submission = Arrays.copyOf(hash(slice(raw, 24, wireLength + EVIDENCE_BYTES)), 16);
		submission[6] = (byte)((submission[6] & 15) | 0x50);
		submission[8] = (byte)((submission[8] & 63) | 0x80);
		require(Arrays.equals(submission, slice(raw, 8, 16)));
		Credential own = credential(slice(evidence, 80, 357), occurred, true, serverKey, keys);
		checkProfile(ownProfileWire, own);
		require(expires - occurred == own.opportunitySeconds());
		verifySignature(own.publicKey(), message("TINGLE-ENCOUNTER-V2\n", hash(slice(raw, 0, raw.length - 64))),
			slice(raw, raw.length - 64, 64));
		Credential peer = evidence[794] == 1
			? credential(slice(evidence, 437, 357), occurred, false, serverKey, keys) : null;
		byte[] peerProfile = null;
		if (kind == Kind.CONFIRMED) {
			require(peer != null && le(wire).getLong(24) > 0 && le(wire).getLong(24) == peer.hardwareRef()
				&& own.hardwareRef() != peer.hardwareRef() && own.deviceId() != peer.deviceId()
				&& !own.ownerRefHex().equals(peer.ownerRefHex()) && peerLength == 248);
			peerProfile = boundPeerProfile(wire, peerPacket, own, peer);
		} else if (peerLength != 0) {
			// Unbound diagnostic bytes may contain another person's profile. Retention/withdrawal is unresolved.
			if (peer == null) {
				throw unresolved();
			}
			try {
				peerProfile = boundPeerProfile(wire, peerPacket, own, peer);
			} catch (BusinessException exception) {
				throw unresolved();
			}
		}
		return new VerifiedEncounter(uuid(raw, 8), uuid(wire, 8), u32(wire, 4), kind,
			Byte.toUnsignedInt(wire[3]), Instant.ofEpochSecond(occurred), Instant.ofEpochSecond(expires),
			own, peer, expectedHash, raw, ownProfile, peerProfile, peerPacket);
	}

	private static byte[] boundPeerProfile(byte[] wire, byte[] packet, Credential own, Credential peer) {
		require(peer != null && packet.length == 248 && le(wire).getLong(24) > 0
			&& le(wire).getLong(24) == peer.hardwareRef());
		require(packet[0] == 'T' && packet[1] == 'K' && packet[2] == 1 && packet[3] == 1
			&& u16(packet, 4) == 238 && le(packet).getLong(6) == peer.hardwareRef());
		CRC32 crc = new CRC32();
		crc.update(packet, 0, packet.length - 4);
		require(u32(packet, packet.length - 4) == crc.getValue());
		byte[] profile = slice(packet, 14, 230);
		checkProfile(profile, peer);
		require(own.policyVersion() == peer.policyVersion() && own.ruleVersion() == peer.ruleVersion()
			&& own.opportunitySeconds() == peer.opportunitySeconds());
		return slice(profile, 34, 196);
	}

	private static Credential credential(byte[] raw, long occurred, boolean sender, byte[] server,
		CredentialKeyResolver keys) {
		int type = Byte.toUnsignedInt(raw[0]);
		if (type != 3 && type != 4) {
			throw unresolved();
		}
		long device = positive(raw, 9);
		long revision = u32(raw, 17);
		long epoch = type == 4 ? u32(raw, 21) : le(raw).getLong(21);
		long opportunity = type == 4 ? u32(raw, 25) : 7L * 86400;
		long policy = u32(raw, 109);
		long rule = u32(raw, 113);
		long until = positive(raw, 117);
		long issued = positive(raw, 125);
		long hardware = le(raw).getLong(1) & Long.MAX_VALUE;
		require(hardware != 0 && revision > 0 && epoch >= 0 && opportunity > 0 && opportunity <= 365L * 86400
			&& until > issued && occurred < until && issued <= occurred + 300
			&& until <= Instant.MAX.getEpochSecond() && nonzero(raw, 93, 16));
		if (policy != POLICY || rule != RULE) {
			throw unresolved();
		}
		UUID owner = uuid(raw, 29);
		UntrustedCredential lookup = new UntrustedCredential(device, hex(slice(raw, 1, 8)), owner, epoch,
			Instant.ofEpochSecond(occurred), sender);
		byte[] trustedKey = keys.resolve(lookup);
		if (trustedKey == null) {
			throw unresolved();
		}
		require(trustedKey.length == 32 && Arrays.equals(trustedKey, slice(raw, 165, 32)));
		verifySignature(server, message(type == 4 ? "TINGLE-PEER-GRANT-V3\n" : "TINGLE-PEER-GRANT-V2\n",
			slice(raw, 0, 197)), slice(raw, 197, 64));
		verifySignature(trustedKey, message("TINGLE-PEER-AUTH-V1\n", slice(raw, 325, 32), slice(raw, 0, 261)),
			slice(raw, 261, 64));
		return new Credential(device, hardware, lookup.hardwareRefHex(), owner, hex(slice(raw, 93, 16)), epoch,
			uuid(raw, 45), revision, hex(slice(raw, 133, 32)), policy, rule, opportunity,
			Instant.ofEpochSecond(issued), Instant.ofEpochSecond(until), slice(raw, 165, 32), raw);
	}

	private static void checkProfile(byte[] profile, Credential credential) {
		require(profile.length == 230 && u16(profile, 32) == 196 && magic(profile, 34, "TPS1")
			&& u32(profile, 0) == credential.settingsVersion() && u32(profile, 4) == credential.policyVersion()
			&& u32(profile, 8) == credential.ruleVersion() && u32(profile, 12) == credential.opportunitySeconds()
			&& hex(hash(slice(profile, 34, 196))).equals(credential.profileHash())
			&& Arrays.equals(slice(profile, 16, 16),
				HexFormat.of().parseHex(credential.profileHash().substring(0, 32))));
	}

	private static void verifySignature(byte[] publicKey, byte[] message, byte[] signature) {
		try {
			byte[] encoded = ByteBuffer.allocate(44).put(SPKI).put(publicKey).array();
			var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
			var verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key);
			verifier.update(message);
			require(verifier.verify(signature));
		} catch (GeneralSecurityException exception) {
			throw invalid();
		}
	}

	private static byte[] message(String domain, byte[]... parts) {
		byte[] prefix = domain.getBytes(StandardCharsets.US_ASCII);
		int length = prefix.length;
		for (byte[] part : parts) {
			length += part.length;
		}
		ByteBuffer result = ByteBuffer.allocate(length).put(prefix);
		for (byte[] part : parts) {
			result.put(part);
		}
		return result.array();
	}

	private static UUID uuid(byte[] bytes, int offset) {
		ByteBuffer source = ByteBuffer.wrap(bytes, offset, 16);
		return new UUID(source.getLong(), source.getLong());
	}

	private static ByteBuffer le(byte[] bytes) {
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static int u16(byte[] bytes, int offset) {
		return Short.toUnsignedInt(le(bytes).getShort(offset));
	}

	private static long u32(byte[] bytes, int offset) {
		return Integer.toUnsignedLong(le(bytes).getInt(offset));
	}

	private static long positive(byte[] bytes, int offset) {
		long result = le(bytes).getLong(offset);
		require(result > 0);
		return result;
	}

	private static byte[] slice(byte[] bytes, int offset, int length) {
		return Arrays.copyOfRange(bytes, offset, offset + length);
	}

	private static boolean magic(byte[] bytes, int offset, String value) {
		return Arrays.equals(slice(bytes, offset, value.length()), value.getBytes(StandardCharsets.US_ASCII));
	}

	private static boolean nonzero(byte[] bytes, int offset, int length) {
		for (int index = offset; index < offset + length; index++) {
			if (bytes[index] != 0) {
				return true;
			}
		}
		return false;
	}

	private static byte[] hash(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	private static void require(boolean condition) {
		if (!condition) {
			throw invalid();
		}
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	/**
	 * Lookup values are untrusted until verify returns; never enroll a key from this callback.
	 * The adapter must bind sender=true to the authenticated request's expected device and historical owner.
	 */
	@FunctionalInterface
	public interface CredentialKeyResolver {
		byte[] resolve(UntrustedCredential identity);
	}

	public enum Kind {
		CONFIRMED, REVIEW
	}

	public record UntrustedCredential(long deviceId, String hardwareRefHex, UUID ownerGeneration,
		long ownershipEpoch, Instant occurredAt, boolean sender) {
	}

	public record Credential(long deviceId, long hardwareRef, String hardwareRefHex, UUID ownerGeneration,
		String ownerRefHex, long ownershipEpoch, UUID syncRunId, long settingsVersion, String profileHash,
		long policyVersion, long ruleVersion, long opportunitySeconds, Instant issuedAt, Instant validUntil,
		byte[] publicKey, byte[] raw) {
		public Credential {
			publicKey = publicKey.clone();
			raw = raw.clone();
		}

		@Override
		public byte[] publicKey() {
			return publicKey.clone();
		}

		@Override
		public byte[] raw() {
			return raw.clone();
		}
	}

	public record VerifiedEncounter(UUID submissionId, UUID eventId, long sequence, Kind kind, int reviewReason,
		Instant occurredAt, Instant expiresAt, Credential own, Credential peer, String envelopeHash,
		byte[] raw, byte[] ownProfile, byte[] peerProfile, byte[] peerPacket) {
		public VerifiedEncounter {
			raw = raw.clone();
			ownProfile = ownProfile.clone();
			peerProfile = peerProfile == null ? null : peerProfile.clone();
			peerPacket = peerPacket.clone();
		}

		@Override
		public byte[] raw() {
			return raw.clone();
		}

		@Override
		public byte[] ownProfile() {
			return ownProfile.clone();
		}

		@Override
		public byte[] peerProfile() {
			return peerProfile == null ? null : peerProfile.clone();
		}

		@Override
		public byte[] peerPacket() {
			return peerPacket.clone();
		}
	}
}

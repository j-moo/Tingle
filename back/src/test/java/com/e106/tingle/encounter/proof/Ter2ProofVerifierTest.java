package com.e106.tingle.encounter.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

import com.e106.tingle.encounter.proof.Ter2ProofVerifier.Kind;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

class Ter2ProofVerifierTest {

	private final Ter2ProofVerifier verifier = new Ter2ProofVerifier();

	@Test
	void verifiesIndependentFirmwareV2V3AndOwnerVectorsWithoutChangingRawEvidence() throws Exception {
		for (String name : List.of("v2", "v3", "owner")) {
			Fixture data = data(name);
			byte[] preserved = data.raw().clone();
			var proof = verifier.verify(data.raw(), hash(data.raw()), data.server(), data.sender(), data.peer());
			assertThat(proof.kind()).isEqualTo(Kind.CONFIRMED);
			assertThat(proof.own().deviceId()).isEqualTo(100);
			assertThat(proof.peer().deviceId()).isEqualTo(101);
			assertThat(proof.own().ownershipEpoch()).isEqualTo(1);
			assertThat(proof.own().syncRunId()).isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));
			assertThat(proof.own().settingsVersion()).isEqualTo(10);
			assertThat(proof.ownProfile()).hasSize(196);
			assertThat(proof.peerProfile()).hasSize(196);
			assertThat(proof.own().raw()).hasSize(357);
			assertThat(proof.expiresAt().getEpochSecond() - proof.occurredAt().getEpochSecond())
				.isEqualTo(proof.own().opportunitySeconds());
			assertThat(proof.raw()).isEqualTo(preserved);
			assertThat(data.raw()).isEqualTo(preserved);
			byte[] changed = proof.raw();
			changed[0] = 0;
			assertThat(proof.raw()).isEqualTo(preserved);
		}
	}

	@Test
	void rejectsAnyEnvelopeCredentialSignatureOrFrozenProfileTampering() throws Exception {
		Fixture data = data("v3");
		int evidence = 24 + Short.toUnsignedInt(le(data.raw()).getShort(4));
		for (int offset : new int[] {8, 40, 24 + 52 + 34, evidence + 16, evidence + 80 + 197,
			evidence + 80 + 261, evidence + 437 + 197, evidence + 437 + 261, data.raw().length - 1}) {
			byte[] changed = data.raw().clone();
			changed[offset] ^= 1;
			assertInvalid(() -> verifier.verify(changed, hash(changed), data.server(), data.sender(), data.peer()));
		}
		assertInvalid(() -> verifier.verify(data.raw(), "0".repeat(64), data.server(), data.sender(), data.peer()));
	}

	@Test
	void refusesSelfDeclaredDeviceKeysAndWrongServerTrust() throws Exception {
		Fixture data = data("v3");
		byte[] other = new byte[32];
		assertInvalid(() -> verifier.verify(data.raw(), hash(data.raw()), other, data.sender(), data.peer()));
		assertInvalid(() -> verifier.verify(data.raw(), hash(data.raw()), data.server(), other, data.peer()));
		assertInvalid(() -> verifier.verify(data.raw(), hash(data.raw()), data.server(), data.sender(), other));
		assertThatThrownBy(() -> verifier.verify(data.raw(), hash(data.raw()), data.server(), identity -> null))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
	}

	@Test
	void validSenderSignatureCannotHideEitherInvalidGrantOrAuthenticationSignature() throws Exception {
		Fixture data = data("v3");
		int evidence = 24 + Short.toUnsignedInt(le(data.raw()).getShort(4));
		for (int credential : new int[] {evidence + 80, evidence + 437}) {
			for (int offset : new int[] {197, 261, 325}) {
				byte[] altered = data.raw().clone();
				altered[credential + offset] ^= 1;
				resignSyntheticSender(altered);
				assertInvalid(() -> verifier.verify(altered, hash(altered), data.server(), data.sender(), data.peer()));
			}
		}
	}

	@Test
	void validSenderSignatureCannotSwapOrDuplicateParticipantCredentials() throws Exception {
		Fixture data = data("v3");
		int evidence = 24 + Short.toUnsignedInt(le(data.raw()).getShort(4));
		for (boolean swap : new boolean[] {false, true}) {
			byte[] altered = data.raw().clone();
			byte[] own = Arrays.copyOfRange(altered, evidence + 80, evidence + 437);
			if (swap) {
				System.arraycopy(altered, evidence + 437, altered, evidence + 80, 357);
			}
			System.arraycopy(own, 0, altered, evidence + 437, 357);
			resignSyntheticSender(altered);
			assertInvalid(() -> verifier.verify(altered, hash(altered), data.server(), data.sender(), data.peer()));
		}
	}

	@Test
	void suppliesExplicitlyUntrustedHistoricalLookupClaimsBeforeReturningVerifiedResult() throws Exception {
		Fixture data = data("v3");
		int[] lookedUp = {0};
		var proof = verifier.verify(data.raw(), hash(data.raw()), data.server(), identity -> {
			lookedUp[0]++;
			assertThat(identity.ownershipEpoch()).isEqualTo(1);
			assertThat(identity.occurredAt().getEpochSecond()).isEqualTo(1793577900);
			assertThat(identity.hardwareRefHex()).isEqualTo(identity.sender()
				? "0100000000000000" : "0200000000000000");
			return identity.sender() ? data.sender() : data.peer();
		});
		assertThat(lookedUp[0]).isEqualTo(2);
		assertThat(proof.peer().hardwareRef()).isEqualTo(2);
	}

	@Test
	void signedReviewWithoutPeerStaysReviewAndNeverBecomesConfirmed() throws Exception {
		Fixture data = data("v3");
		byte[] review = emptyPeerReview(data.raw());
		var proof = verifier.verify(review, hash(review), data.server(), data.sender(), null);
		assertThat(proof.kind()).isEqualTo(Kind.REVIEW);
		assertThat(proof.reviewReason()).isEqualTo(2);
		assertThat(proof.peer()).isNull();
		assertThat(proof.peerProfile()).isNull();
		assertThat(proof.raw()).isEqualTo(review);
	}

	@Test
	void signedReviewWithAuthenticatedAndExactlyBoundPeerProfileRemainsSupported() throws Exception {
		Fixture data = data("v3");
		byte[] review = data.raw().clone();
		review[25] = 3;
		review[27] = 2;
		resignSyntheticSender(review);
		var proof = verifier.verify(review, hash(review), data.server(), data.sender(), data.peer());
		assertThat(proof.kind()).isEqualTo(Kind.REVIEW);
		assertThat(proof.peerProfile()).hasSize(196);
		assertThat(proof.peer().deviceId()).isEqualTo(101);
	}

	@Test
	void refusesNonemptyReviewProfileWithoutPeerAuthOrWithAnotherProfileEvenWhenSenderSignsIt() throws Exception {
		Fixture data = data("v3");
		for (boolean missingPeer : new boolean[] {true, false}) {
			byte[] review = data.raw().clone();
			review[25] = 3;
			review[27] = 2;
			int evidence = 24 + Short.toUnsignedInt(le(review).getShort(4));
			if (missingPeer) {
				review[evidence + 794] = 0;
				Arrays.fill(review, evidence + 437, evidence + 794, (byte)0);
			} else {
				int packet = 24 + 52 + 230;
				review[packet + 14 + 34 + 100] ^= 1;
				CRC32 crc = new CRC32();
				crc.update(review, packet, 244);
				le(review).putInt(packet + 244, (int)crc.getValue());
				System.arraycopy(sha(Arrays.copyOfRange(review, packet, packet + 248)), 0, review, evidence + 48, 32);
			}
			resignSyntheticSender(review);
			assertThatThrownBy(() -> verifier.verify(review, hash(review), data.server(), data.sender(), data.peer()))
				.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		}
	}

	@Test
	void rejectsValidlySignedImpossibleDatesLengthsSequenceAndNormalWithoutPeer() throws Exception {
		Fixture data = data("v3");
		for (int change = 0; change < 5; change++) {
			byte[] altered = data.raw().clone();
			int evidence = 24 + Short.toUnsignedInt(le(altered).getShort(4));
			switch (change) {
				case 0 -> le(altered).putLong(24 + 40, Long.MAX_VALUE);
				case 1 -> le(altered).putLong(24 + 40, le(altered).getLong(24 + 32));
				case 2 -> altered[evidence + 794] = 0;
				case 3 -> le(altered).putInt(24 + 4, 0);
				default -> le(altered).putShort(24 + 48, (short)229);
			}
			resignSyntheticSender(altered);
			assertInvalid(() -> verifier.verify(altered, hash(altered), data.server(), data.sender(), data.peer()));
		}
		for (int length : new int[] {0, 24, data.raw().length - 1, data.raw().length + 1, 3000}) {
			byte[] altered = Arrays.copyOf(data.raw(), length);
			assertInvalid(() -> verifier.verify(altered, hash(altered), data.server(), data.sender(), data.peer()));
		}
	}

	private static void assertInvalid(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
	}

	private static byte[] emptyPeerReview(byte[] original) throws Exception {
		int evidence = 24 + Short.toUnsignedInt(le(original).getShort(4));
		byte[] result = new byte[24 + 282 + 795 + 64];
		System.arraycopy(original, 0, result, 0, 24 + 282);
		System.arraycopy(original, evidence, result, 24 + 282, 795);
		le(result).putShort(4, (short)282).putShort(24 + 50, (short)0);
		result[25] = 3;
		result[27] = 2;
		int moved = 24 + 282;
		System.arraycopy(sha(new byte[0]), 0, result, moved + 48, 32);
		Arrays.fill(result, moved + 437, moved + 795, (byte)0);
		resignSyntheticSender(result);
		return result;
	}

	private static Fixture data(String name) throws Exception {
		String resource = "/keyring/app-flow-" + name + "-vectors.json";
		try (var input = Ter2ProofVerifierTest.class.getResourceAsStream(resource)) {
			var source = JsonMapper.builder().build().readTree(input).path("vectors");
			byte[] raw = Base64.getUrlDecoder().decode(source.path("encounter").path("base64url").asString());
			int evidence = 24 + Short.toUnsignedInt(le(raw).getShort(4));
			byte[] server = Base64.getUrlDecoder().decode(source.path("serverPublic").path("base64url").asString());
			return new Fixture(raw, server,
				Arrays.copyOfRange(raw, evidence + 245, evidence + 277),
				Arrays.copyOfRange(raw, evidence + 602, evidence + 634));
		}
	}

	/** Public host data uses sender seed0x01 repeated32; never reads a real device/server key. */
	private static void resignSyntheticSender(byte[] raw) throws Exception {
		byte[] digest = sha(Arrays.copyOfRange(raw, 24, raw.length - 64));
		System.arraycopy(digest, 0, raw, 8, 16);
		raw[14] = (byte)((raw[14] & 15) | 0x50);
		raw[16] = (byte)((raw[16] & 63) | 0x80);
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte)1);
		byte[] encoded = ByteBuffer.allocate(48)
			.put(HexFormat.of().parseHex("302e020100300506032b657004220420")).put(seed).array();
		var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
		var signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update("TINGLE-ENCOUNTER-V2\n".getBytes(StandardCharsets.US_ASCII));
		signer.update(sha(Arrays.copyOf(raw, raw.length - 64)));
		System.arraycopy(signer.sign(), 0, raw, raw.length - 64, 64);
	}

	private static ByteBuffer le(byte[] bytes) {
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static String hash(byte[] bytes) {
		return HexFormat.of().formatHex(sha(bytes));
	}

	private static byte[] sha(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Fixture(byte[] raw, byte[] server, byte[] sender, byte[] peer) {
	}
}

package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.keyring.proof.PeerSettingsCodec.GrantClaims;
import com.e106.tingle.keyring.proof.PeerSettingsCodec.Restriction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PeerSettingsCodecTest {

	private static final Instant ISSUED = Instant.ofEpochSecond(1793577900);
	private static final Instant UNTIL = Instant.ofEpochSecond(1794787500);
	private static final long POLICY = 0x52504331L;
	private static final long RULE = 0x10001101L;

	@Test
	void matchesIndependentFirmwareBytesHashAndEd25519GrantSignature() throws Exception {
		JsonNode vectors = vectors();
		byte[] expected = decode(vectors.path("bundle").path("base64url").asString());
		byte[] grant = decode(vectors.path("grant").path("base64url").asString());
		byte[] profile = Arrays.copyOfRange(expected, 4, 200);
		byte[] signature = Arrays.copyOfRange(grant, 197, 261);
		byte[] transcript = PeerSettingsCodec.grantTranscript(claims(), profile);
		byte[] encoded = PeerSettingsCodec.encode(claims(), profile, signature, 5, true, true, List.of());
		assertThat(encoded).isEqualTo(expected);
		assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)))
			.isEqualTo(vectors.path("bundle").path("sha256").asString());
		assertThat(Arrays.copyOfRange(transcript, 21, transcript.length)).isEqualTo(Arrays.copyOf(grant, 197));
		assertThat(transcript).hasSize(218);
		var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(HexFormat.of().parseHex(
			"302a300506032b657003210003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8")));
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(transcript);
		assertThat(verifier.verify(signature)).isTrue();
		transcript[21 + 25] ^= 1;
		verifier.initVerify(key);
		verifier.update(transcript);
		assertThat(verifier.verify(signature)).isFalse();
		assertThat(PeerSettingsCodec.validPayloadFraming(encoded)).isTrue();
	}

	@Test
	void encodesUint32VersionAndEpochWithoutSignedTruncation() throws Exception {
		GrantClaims maximum = claims(0xffffffffL, 0xffffffffL, 365L * 86400, UNTIL, ISSUED);
		byte[] encoded = encode(maximum, List.of());
		ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
		assertThat(Integer.toUnsignedLong(bytes.getInt(217))).isEqualTo(0xffffffffL);
		assertThat(Integer.toUnsignedLong(bytes.getInt(221))).isEqualTo(0xffffffffL);
		assertThat(Integer.toUnsignedLong(bytes.getInt(225))).isEqualTo(365L * 86400);
		assertThat(PeerSettingsCodec.validPayloadFraming(encoded)).isTrue();
		assertThat(PeerSettingsCodec.validPayloadFraming(encode(claims(1, 0, 1, UNTIL, ISSUED), List.of())))
			.isTrue();
	}

	@Test
	void rejectsOverflowAndInvalidTimeInsteadOfWrapping() throws Exception {
		byte[] profile = profile();
		for (GrantClaims invalid : List.of(claims(0, 0, 1, UNTIL, ISSUED),
			claims(0x100000000L, 0, 1, UNTIL, ISSUED), claims(1, -1, 1, UNTIL, ISSUED),
			claims(1, 0x100000000L, 1, UNTIL, ISSUED), claims(1, 0, 0, UNTIL, ISSUED),
			claims(1, 0, 365L * 86400 + 1, UNTIL, ISSUED), claims(1, 0, 1, ISSUED, ISSUED),
			claims(1, 0, 1, UNTIL, Instant.EPOCH), claims(1, 0, 1, UNTIL, ISSUED.plusNanos(1)))) {
			assertThatThrownBy(() -> PeerSettingsCodec.grantTranscript(invalid, profile))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void preservesIncompletePolicyAndEncodesTimedAndPermanentRestrictions() throws Exception {
		byte[] owner = new byte[16];
		owner[0] = 42;
		byte[] other = owner.clone();
		other[0] = 43;
		List<Restriction> restrictions = List.of(new Restriction(owner, 1, 1800000000),
			new Restriction(other, 2, 0));
		byte[] encoded = PeerSettingsCodec.encode(claims(), profile(), new byte[64], 300, false, false,
			restrictions);
		assertThat(encoded).hasSize(537);
		assertThat(encoded[481]).isZero();
		assertThat(encoded[482]).isZero();
		assertThat(Arrays.copyOfRange(encoded, 487, 503)).isEqualTo(owner);
		assertThat(encoded[503]).isEqualTo((byte)1);
		assertThat(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getLong(504)).isEqualTo(1800000000);
		assertThat(PeerSettingsCodec.validPayloadFraming(encoded)).isTrue();
	}

	@Test
	void rejectsExcessRestrictionsDuplicatesAndInvalidExpiry() throws Exception {
		byte[] profile = profile();
		List<Restriction> maximum = new ArrayList<>();
		for (int index = 1; index <= 60; index++) {
			byte[] owner = new byte[16];
			owner[0] = (byte)index;
			maximum.add(new Restriction(owner, 2, 0));
		}
		byte[] encoded = encode(claims(), maximum);
		assertThat(encoded).hasSize(1987);
		assertThat(PeerSettingsCodec.validPayloadFraming(encoded)).isTrue();
		maximum.add(maximum.getFirst());
		assertThatThrownBy(() -> encode(claims(), maximum)).isInstanceOf(IllegalArgumentException.class);
		byte[] owner = new byte[16];
		owner[0] = 1;
		for (List<Restriction> invalid : List.of(List.of(new Restriction(owner, 1, 0)),
			List.of(new Restriction(owner, 2, 1)), List.of(new Restriction(owner, 3, 0)),
			List.of(new Restriction(new byte[16], 2, 0)),
			List.of(new Restriction(owner, 2, 0), new Restriction(owner.clone(), 1, 1800000000)))) {
			assertThatThrownBy(() -> PeerSettingsCodec.encode(claims(), profile, new byte[64], 5, true, true, invalid))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsMalformedFramingAndInconsistentGrantMetadata() throws Exception {
		byte[] encoded = encode(claims(), List.of());
		assertThat(PeerSettingsCodec.validPayloadFraming(null)).isFalse();
		assertThat(PeerSettingsCodec.validPayloadFraming(Arrays.copyOf(encoded, 486))).isFalse();
		assertThat(PeerSettingsCodec.validPayloadFraming(Arrays.copyOf(encoded, 488))).isFalse();
		for (int offset : new int[] {0, 4, 8, 200, 309, 313, 317, 333, 461, 465, 469, 477, 481, 482, 483}) {
			byte[] changed = encoded.clone();
			changed[offset] ^= 2;
			assertThat(PeerSettingsCodec.validPayloadFraming(changed)).as("changed offset %s", offset).isFalse();
		}
		byte[] changed = encoded.clone();
		Arrays.fill(changed, 293, 309, (byte)0);
		assertThat(PeerSettingsCodec.validPayloadFraming(changed)).isFalse();
	}

	@Test
	void refusesMalformedProfileSignatureLengthAndPresenceGap() throws Exception {
		byte[] profile = profile();
		for (int length : new int[] {0, 195, 197}) {
			byte[] changed = Arrays.copyOf(profile, length);
			assertThatThrownBy(() -> PeerSettingsCodec.grantTranscript(claims(), changed))
				.isInstanceOf(IllegalArgumentException.class);
		}
		for (int presence : new int[] {0, 301}) {
			assertThatThrownBy(() -> PeerSettingsCodec.encode(claims(), profile, new byte[64], presence,
				true, true, List.of())).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> PeerSettingsCodec.encode(claims(), profile, new byte[63], 5,
			true, true, List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	private static GrantClaims claims() {
		return claims(10, 1, 259200, UNTIL, ISSUED);
	}

	private static GrantClaims claims(long version, long epoch, long opportunity, Instant until, Instant issued) {
		byte[] owner = new byte[16];
		Arrays.fill(owner, (byte)1);
		return new GrantClaims(new byte[] {1, 0, 0, 0, 0, 0, 0, 0}, 100, version, epoch, opportunity,
			UUID.fromString("00000001-3333-4333-8333-333333333333"),
			UUID.fromString("22222222-2222-4222-8222-222222222222"), new byte[32], owner, POLICY, RULE, until, issued,
			HexFormat.of().parseHex("8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"));
	}

	private static byte[] encode(GrantClaims claims, List<Restriction> restrictions) throws Exception {
		return PeerSettingsCodec.encode(claims, profile(), new byte[64], 5, true, true, restrictions);
	}

	private static byte[] profile() throws Exception {
		return Arrays.copyOfRange(decode(vectors().path("bundle").path("base64url").asString()), 4, 200);
	}

	private static JsonNode vectors() throws Exception {
		try (var input = PeerSettingsCodecTest.class.getResourceAsStream("/keyring/app-flow-v3-vectors.json")) {
			return JsonMapper.builder().build().readTree(input).path("vectors");
		}
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}
}

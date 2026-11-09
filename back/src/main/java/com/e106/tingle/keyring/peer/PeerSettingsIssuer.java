package com.e106.tingle.keyring.peer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.DevSettingsIssuer;
import com.e106.tingle.keyring.Keyring;
import com.e106.tingle.keyring.KeyringSyncRun;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.PeerSettingsCodec;
import com.e106.tingle.keyring.proof.PeerSettingsCodec.GrantClaims;

import lombok.RequiredArgsConstructor;

/** Explicitly opted-in TSV3 issuer. The caller holds member/settings/device locks in that order. */
@Service
@ConditionalOnProperty(name = "app.keyring.peer.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PeerSettingsIssuer {

	private final PeerOwnershipService owners;
	private final PeerSettingsPolicyProvider policies;
	private final ObjectProvider<DevSettingsIssuer> signers;
	@Value("${app.keyring.peer.device-serials:}")
	private Set<String> deviceSerials;
	@Value("${app.keyring.peer.server-public-key-hex:}")
	private String expectedServerPublicKey;

	public void requireSupported(Keyring device) {
		if (!deviceSerials.contains(device.getSerialNumber()) || device.getOwnershipEpoch() == null
			|| device.getOwnershipEpoch() < 0 || device.getOwnershipEpoch() > 0xffff_ffffL
			|| !device.getSerialNumber().matches("XIAO-[0-9a-f]{16}")) {
			throw unresolved();
		}
		signer();
	}

	public byte[] issue(Keyring device, UUID run, String nonce, long version, byte[] profile, Instant now) {
		requireSupported(device);
		Instant issued = now.truncatedTo(ChronoUnit.SECONDS);
		var owner = owners.current(device, issued);
		var policy = policies.current(device.getOwnerUserId(), issued);
		try {
			byte[] publicKey = KeyringProofTranscript.decodeBase64Url(device.getCredentialPublicKey(), 44);
			if (!HexFormat.of().formatHex(publicKey, 0, 12).equals("302a300506032b6570032100")) {
				throw unresolved();
			}
			byte[] raw = Arrays.copyOfRange(publicKey, 12, 44);
			if (!device.getCredentialKeyId().equals("ed25519-" + HexFormat.of().formatHex(raw, 0, 8))) {
				throw unresolved();
			}
			GrantClaims claims = new GrantClaims(HexFormat.of().parseHex(device.getSerialNumber().substring(5)),
				device.getId(), version, device.getOwnershipEpoch(), policy.opportunitySeconds(),
				device.getOwnerGeneration(), run, KeyringProofTranscript.decodeBase64Url(nonce, 32),
				PeerSettingsPolicyProvider.uuid(owner.ownerRef()), policy.policyVersion(), policy.ruleVersion(),
				issued.plusSeconds(policy.leaseSeconds()), issued, raw);
			byte[] signature = Base64.getUrlDecoder().decode(signer().signCanonical(
				PeerSettingsCodec.grantTranscript(claims, profile)));
			return PeerSettingsCodec.encode(claims, profile, signature, policy.presenceGapSeconds(),
				policy.complete(), policy.deviceAllowed(), policy.restrictions());
		} catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException exception) {
			throw unresolved();
		}
	}

	/** A fresh nonce signs only the outer proof while the signed grant/run/payload remain immutable. */
	public boolean reusable(KeyringSyncRun run, Keyring device, byte[] profile, Instant now) {
		requireSupported(device);
		byte[] payload = run.getPayload();
		if (!PeerSettingsCodec.FORMAT.equals(run.getPayloadFormat()) || !PeerSettingsCodec.validPayloadFraming(payload)
			|| !Arrays.equals(profile, Arrays.copyOfRange(payload, 4, 200))
			|| !device.getOwnerGeneration().equals(run.getOwnerGeneration())
			|| !device.getOwnershipEpoch().equals(run.getOwnershipEpoch())
			|| !device.getCredentialKeyId().equals(run.getCredentialKeyId())) {
			return false;
		}
		var owner = owners.current(device, now);
		var policy = policies.current(device.getOwnerUserId(), now);
		ByteBuffer fields = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
		Instant issued = Instant.ofEpochSecond(fields.getLong(325));
		Instant until = Instant.ofEpochSecond(fields.getLong(317));
		if (now.isBefore(issued) || !now.isBefore(until)
			|| until.getEpochSecond() - issued.getEpochSecond() != policy.leaseSeconds()
			|| !Arrays.equals(PeerSettingsPolicyProvider.uuid(owner.ownerRef()),
				Arrays.copyOfRange(payload, 293, 309))) {
			return false;
		}
		try {
			byte[] grant = Arrays.copyOfRange(payload, 200, 461);
			byte[] raw = Arrays.copyOfRange(grant, 165, 197);
			byte[] devicePublic = KeyringProofTranscript.decodeBase64Url(device.getCredentialPublicKey(), 44);
			if (!Arrays.equals(raw, Arrays.copyOfRange(devicePublic, 12, 44))) {
				return false;
			}
			GrantClaims claims = new GrantClaims(Arrays.copyOfRange(grant, 1, 9), run.getKeyringId(),
				run.getSettingsVersion(), run.getOwnershipEpoch(), policy.opportunitySeconds(),
				run.getOwnerGeneration(),
				run.getSyncRunId(), KeyringProofTranscript.decodeBase64Url(run.getNonce(), 32),
				PeerSettingsPolicyProvider.uuid(owner.ownerRef()), policy.policyVersion(), policy.ruleVersion(), until,
				issued, raw);
			byte[] signature = Arrays.copyOfRange(grant, 197, 261);
			byte[] expected = PeerSettingsCodec.encode(claims, profile, signature, policy.presenceGapSeconds(),
				policy.complete(), policy.deviceAllowed(), policy.restrictions());
			if (!Arrays.equals(expected, payload)) {
				return false;
			}
			var verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
				HexFormat.of().parseHex("302a300506032b6570032100" + signer().publicKeyRawHex()))));
			verifier.update(PeerSettingsCodec.grantTranscript(claims, profile));
			return verifier.verify(signature);
		} catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
			return false;
		}
	}

	private DevSettingsIssuer signer() {
		DevSettingsIssuer signer = signers.getIfAvailable();
		if (signer == null || !expectedServerPublicKey.matches("[0-9a-f]{64}")
			|| !expectedServerPublicKey.equals(signer.publicKeyRawHex())) {
			throw unresolved();
		}
		return signer;
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}
}

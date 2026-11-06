package com.e106.tingle.keyring;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.dto.KeyringView;
import com.e106.tingle.keyring.dto.RegisterKeyringRequest;
import com.e106.tingle.keyring.proof.RegistrationProofVerifier;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KeyringRegistrationService {

	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final OnboardingService onboarding;
	private final RegistrationChallengeRepository challenges;
	private final KeyringRegistrationRepository keyrings;
	private final KeyringReadRepository reads;
	private final Clock clock;
	private final RegistrationProofVerifier verifier = new RegistrationProofVerifier();

	@Transactional
	public KeyringView register(long userId, UUID familyId, UUID requestKey, RegisterKeyringRequest request) {
		if (requestKey == null || request == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		users.findByIdForUpdate(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_REVOKED));
		requireSession(userId, familyId, clock.instant());
		if (!onboarding.canAccess(userId, OnboardingStatus.KEYRING_REQUIRED)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		UUID challengeId = UUID.fromString(request.challengeId());
		String requestHash = requestHash(request);
		RegistrationChallenge previous = challenges.findByUserIdAndRegistrationRequestKey(userId, requestKey)
			.orElse(null);
		if (previous != null && (!previous.getChallengeId().equals(challengeId)
			|| !requestHash.equals(previous.getRegistrationRequestHash()))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		// 발급/등록의 회원 잠금 순서에 이어 challenge와 기기 행을 잠근다.
		RegistrationChallenge challenge = challenges.findOwnedForUpdate(userId, challengeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		if ((previous == null) != (challenge.getConsumedAt() == null)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		Keyring device = keyrings.findBySerialForUpdate(request.deviceSerial())
			.orElseThrow(() -> new BusinessException(previous == null
				? ErrorCode.INVALID_DEVICE_PROOF : ErrorCode.DEVICE_ALREADY_BOUND));
		if (device.getCredentialRevokedAt() != null
			|| !request.proof().keyId().equals(device.getCredentialKeyId())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		boolean epochBound = com.e106.tingle.keyring.proof.KeyringProofTranscript.PROTOCOL_VERSION_2
			.equals(request.proof().protocolVersion());
		if (epochBound ? request.ownershipEpoch() == null || request.ownershipEpoch() < 0
			|| !"UNOWNED".equals(request.ownerState())
			|| (device.getOwnershipEpoch() == null ? request.ownershipEpoch() != 0
				: !device.getOwnershipEpoch().equals(request.ownershipEpoch()))
			: (device.getOwnershipEpoch() != null && device.getOwnershipEpoch() > 0)
				|| request.ownershipEpoch() != null || request.ownerState() != null) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		String proofHash = verifier.verify(challenge, request.deviceSerial(), request.proof().keyId(),
			request.proof().protocolVersion(), request.proof().signature(), device.getCredentialPublicKey(),
			request.ownershipEpoch())
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_PROOF));
		// 잠금 대기/서명 확인 중 기한을 넘긴 요청을 최초 시각으로 소급해 허용하지 않는다.
		Instant now = clock.instant();
		requireSession(userId, familyId, now);
		if (previous != null) {
			return replay(userId, challenge, device, proofHash);
		}
		if (now.isBefore(challenge.getIssuedAt()) || !now.isBefore(challenge.getExpiresAt())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		if (device.getState() != KeyringState.UNBOUND || device.getOwnerUserId() != null) {
			throw new BusinessException(ErrorCode.DEVICE_ALREADY_BOUND);
		}
		UUID generation = UUID.randomUUID();
		if (epochBound) {
			device.confirmOwnershipEpoch(request.ownershipEpoch());
		}
		device.register(userId, generation);
		keyrings.saveAndFlush(device);
		KeyringView result = reads.findOwned(userId).stream().filter(view -> view.deviceId() == device.getId())
			.findFirst().orElseThrow(() -> new IllegalStateException("Registered device is unavailable"));
		challenge.consume(device.getId(), generation, device.getCredentialKeyId(), proofHash, now,
			requestKey, requestHash, result.firmwareVersion(), result.status());
		challenges.flush();
		onboarding.recordProgress(userId);
		return result;
	}

	private KeyringView replay(long userId, RegistrationChallenge challenge, Keyring device, String proofHash) {
		if (device.getState() != KeyringState.REGISTERED || !Long.valueOf(userId).equals(device.getOwnerUserId())
			|| !device.getId().equals(challenge.getRegisteredKeyringId())
			|| !Objects.equals(device.getOwnerGeneration(), challenge.getRegisteredOwnerGeneration())) {
			throw new BusinessException(ErrorCode.DEVICE_ALREADY_BOUND);
		}
		if (!proofHash.equals(challenge.getRegistrationProofHash())
			|| !device.getCredentialKeyId().equals(challenge.getRegisteredKeyId())
			|| challenge.getRegisteredStatus() == null || challenge.getRegisteredFirmwareVersion() == null) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return new KeyringView(challenge.getRegisteredKeyringId(), challenge.getRegisteredStatus(),
			challenge.getRegisteredFirmwareVersion());
	}

	private void requireSession(long userId, UUID familyId, Instant now) {
		if (familyId == null || !sessions.isPushSessionUsable(familyId, userId, now)) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
	}

	private static String requestHash(RegisterKeyringRequest request) {
		try {
			MessageDigest hash = MessageDigest.getInstance("SHA-256");
			for (String value : List.of("TINGLE-KEYRING-REGISTRATION-REQUEST-v1",
				UUID.fromString(request.challengeId()).toString(), request.deviceSerial(),
				request.proof().protocolVersion(), request.proof().keyId(), request.proof().signature())) {
				byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
				hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
				hash.update(bytes);
			}
			if (request.ownershipEpoch() != null || request.ownerState() != null) {
				hash.update(("\nEPOCH:" + request.ownershipEpoch() + ":" + request.ownerState())
					.getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(hash.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}

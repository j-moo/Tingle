package com.e106.tingle.keyring;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.dto.SyncAckRequest;
import com.e106.tingle.keyring.dto.SyncRunView;
import com.e106.tingle.keyring.proof.StoredAckProofVerifier;
import com.e106.tingle.preference.PreferenceUserSettingRepository;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KeyringSyncService {
	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final OnboardingService onboarding;
	private final PreferenceUserSettingRepository settings;
	private final KeyringRegistrationRepository keyrings;
	private final KeyringSyncRunRepository runs;
	private final KeyringPayloadProvider payloads;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final com.e106.tingle.keyring.reset.KeyringResetGuard resetGuard;
	private final ObjectProvider<DevSettingsIssuer> devSettings;
	private final SecureRandom random = new SecureRandom();
	private final StoredAckProofVerifier verifier = new StoredAckProofVerifier();

	@Transactional
	public Created create(long userId, UUID familyId, long deviceId, UUID requestKey) {
		return create(userId, familyId, deviceId, requestKey, null);
	}

	@Transactional
	public Created create(long userId, UUID familyId, long deviceId, UUID requestKey, String deviceNonce) {
		Keyring device = access(userId, familyId, deviceId, OnboardingStatus.SYNC_PENDING);
		requireCredential(device);
		DevSettingsIssuer issuer = devSettings.getIfAvailable();
		if (issuer != null) {
			DevSettingsIssuer.requireDeviceNonce(deviceNonce);
		} else if (deviceNonce != null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		KeyringSyncRun replay = runs.findRequest(deviceId, device.getOwnerGeneration(), requestKey,
			"[\"" + requestKey + "\"]").orElse(null);
		if (replay != null) {
			requireSession(userId, familyId);
			return new Created(issuedView(replay, device, requestKey, deviceNonce, issuer), false);
		}
		KeyringPayload payload = issuer == null ? payloads.prepare(userId, deviceId, device.getOwnerGeneration())
			: issuer.prepare(userId, deviceId, device.getOwnerGeneration());
		// 공급자가 설정 담당 서비스를 통해 버전을 확정했을 수 있으므로 캐시 대신 DB에서 다시 읽는다.
		long current = version(userId);
		if (payload == null || payload.userId() != userId || payload.deviceId() != deviceId
			|| !device.getOwnerGeneration().equals(payload.ownerGeneration()) || payload.settingsVersion() != current) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		requireSession(userId, familyId);
		KeyringSyncRun existing = runs.findByKeyringIdAndOwnerGenerationAndSettingsVersion(
			deviceId, device.getOwnerGeneration(), current).orElse(null);
		if (existing != null) {
			if (!payload.format().equals(existing.getPayloadFormat())
				|| !Arrays.equals(payload.bytes(), existing.getPayload())) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			existing.addRequestKeyAlias(requestKey);
			return new Created(issuedView(existing, device, requestKey, deviceNonce, issuer), false);
		}
		byte[] nonce = new byte[32];
		random.nextBytes(nonce);
		KeyringSyncRun run = new KeyringSyncRun(UUID.randomUUID(), deviceId, userId, device.getOwnerGeneration(),
			device.getCredentialKeyId(), current, payload.bytes(), Base64.getUrlEncoder().withoutPadding()
				.encodeToString(nonce), requestKey, clock.instant(), payload.format());
		if (device.getOwnershipEpoch() != null) {
			run.bindOwnershipEpoch(device.getOwnershipEpoch());
		}
		runs.saveAndFlush(run);
		return new Created(issuedView(run, device, requestKey, deviceNonce, issuer), true);
	}

	private SyncRunView issuedView(KeyringSyncRun run, Keyring device, UUID requestKey,
		String deviceNonce, DevSettingsIssuer issuer) {
		if (issuer == null) {
			return view(run, version(device.getOwnerUserId()));
		}
		if (!Objects.equals(run.getCredentialKeyId(), device.getCredentialKeyId())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		DevSettingsProof proof = run.getServerProofs().get(requestKey.toString());
		if (proof != null && !proof.deviceNonce().equals(deviceNonce)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (proof == null) {
			if (!DevSettingsIssuer.FORMAT.equals(run.getPayloadFormat())) {
				throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
			}
			proof = issuer.sign(run, device.getSerialNumber(), deviceNonce, clock.instant());
			run.rememberServerProof(requestKey, proof);
			runs.flush();
		}
		return view(run, version(device.getOwnerUserId()), proof);
	}

	@Transactional
	public SyncRunView get(long userId, UUID familyId, long deviceId, UUID runId) {
		Keyring device = access(userId, familyId, deviceId, OnboardingStatus.KEYRING_REQUIRED);
		KeyringSyncRun run = ownedRun(userId, device, runId);
		requireSession(userId, familyId);
		return view(run, version(userId));
	}

	@Transactional
	public SyncRunView acknowledge(long userId, UUID familyId, long deviceId, UUID runId,
		UUID requestKey, SyncAckRequest request) {
		Keyring device = access(userId, familyId, deviceId, OnboardingStatus.SYNC_PENDING);
		KeyringSyncRun run = ownedRun(userId, device, runId);
		requireCredential(device);
		if (!Objects.equals(run.getCredentialKeyId(), device.getCredentialKeyId())
			|| !Objects.equals(run.getOwnershipEpoch(), device.getOwnershipEpoch())
			|| request.settingsVersion() != run.getSettingsVersion()
			|| !request.payloadHash().equals(run.getPayloadHash())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		String proofHash = verifier.verify(run, device.getSerialNumber(), request.proof().keyId(),
			request.proof().protocolVersion(), request.proof().signature(), device.getCredentialPublicKey())
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_PROOF));
		Instant now = clock.instant();
		requireSession(userId, familyId);
		long current = version(userId);
		if (now.isBefore(run.getCreatedAt())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		if (run.getState() == KeyringSyncState.ACKED) {
			if (!proofHash.equals(run.getAckProofHash())) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return view(run, current);
		}
		if (current != run.getSettingsVersion()) {
			throw new BusinessException(ErrorCode.SYNC_VERSION_STALE);
		}
		run.acknowledge(requestKey, proofHash, now);
		// 개발 v1 저장은 성향 적용·신뢰 시각 준비를 입증하지 않는다.
		if (!DevSettingsIssuer.FORMAT.equals(run.getPayloadFormat())) {
			device.acknowledgeSettings(current, now);
			onboarding.recordProgress(userId);
		}
		runs.flush();
		return view(run, current);
	}

	private Keyring access(long userId, UUID familyId, long deviceId, OnboardingStatus stage) {
		users.findByIdForUpdate(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_REVOKED));
		requireSession(userId, familyId);
		if (!onboarding.canAccess(userId, stage)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		settings.findForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.ONBOARDING_REQUIRED));
		Keyring device = keyrings.findByIdForUpdate(deviceId).filter(item -> item.getState() == KeyringState.REGISTERED
			&& Long.valueOf(userId).equals(item.getOwnerUserId()) && item.getOwnerGeneration() != null)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		resetGuard.requireNoPendingReset(deviceId);
		return device;
	}

	private KeyringSyncRun ownedRun(long userId, Keyring device, UUID runId) {
		return runs.findForUpdate(device.getId(), runId).filter(run -> run.getOwnerUserId() == userId
			&& device.getOwnerGeneration().equals(run.getOwnerGeneration()))
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private void requireSession(long userId, UUID familyId) {
		if (familyId == null || !sessions.isPushSessionUsable(familyId, userId, clock.instant())) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
	}

	private void requireCredential(Keyring device) {
		if (device.getCredentialRevokedAt() != null || device.getCredentialKeyId() == null
			|| device.getCredentialPublicKey() == null) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
	}

	private long version(long userId) {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=?", Long.class, userId);
	}

	private SyncRunView view(KeyringSyncRun run, long current) {
		return view(run, current, run.getServerProofs().get(run.getRequestKey().toString()));
	}

	private SyncRunView view(KeyringSyncRun run, long current, DevSettingsProof proof) {
		if (run.getPayloadFormat() == null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		return new SyncRunView(run.getSyncRunId(), run.getSyncRunId(), run.getKeyringId(), run.getOwnerGeneration(),
			run.getSettingsVersion(), current, run.getState(), current != run.getSettingsVersion(),
			Base64.getUrlEncoder().withoutPadding().encodeToString(run.getPayload()), "base64url",
			run.getPayloadFormat(),
			run.getPayloadHash(), run.getNonce(), run.getCredentialKeyId(), run.getProtocolVersion(),
			run.getCreatedAt(), run.getAckedAt(), proof == null ? clock.instant() : proof.serverTime(),
			proof == null ? null : proof.protocolVersion(), proof == null ? null : proof.keyId(),
			proof == null ? null : proof.deviceNonce(), proof == null ? null : proof.signature(),
			run.getOwnershipEpoch());
	}

	public record Created(SyncRunView run, boolean created) {
	}
}

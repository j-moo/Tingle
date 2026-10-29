package com.e106.tingle.keyring;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

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
	private final SecureRandom random = new SecureRandom();
	private final StoredAckProofVerifier verifier = new StoredAckProofVerifier();

	@Transactional
	public Created create(long userId, UUID familyId, long deviceId, UUID requestKey) {
		Keyring device = access(userId, familyId, deviceId, OnboardingStatus.SYNC_PENDING);
		requireCredential(device);
		KeyringSyncRun replay = runs.findRequest(deviceId, device.getOwnerGeneration(), requestKey,
			"[\"" + requestKey + "\"]").orElse(null);
		if (replay != null) {
			requireSession(userId, familyId);
			return new Created(view(replay, version(userId)), false);
		}
		KeyringPayload payload = payloads.prepare(userId, deviceId, device.getOwnerGeneration());
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
			return new Created(view(existing, current), false);
		}
		byte[] nonce = new byte[32];
		random.nextBytes(nonce);
		KeyringSyncRun run = new KeyringSyncRun(UUID.randomUUID(), deviceId, userId, device.getOwnerGeneration(),
			device.getCredentialKeyId(), current, payload.bytes(), Base64.getUrlEncoder().withoutPadding()
				.encodeToString(nonce), requestKey, clock.instant(), payload.format());
		runs.saveAndFlush(run);
		return new Created(view(run, current), true);
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
		device.acknowledgeSettings(current, now);
		runs.flush();
		onboarding.recordProgress(userId);
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
		return keyrings.findByIdForUpdate(deviceId).filter(device -> device.getState() == KeyringState.REGISTERED
			&& Long.valueOf(userId).equals(device.getOwnerUserId()) && device.getOwnerGeneration() != null)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
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
		if (run.getPayloadFormat() == null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		return new SyncRunView(run.getSyncRunId(), run.getSyncRunId(), run.getKeyringId(), run.getOwnerGeneration(),
			run.getSettingsVersion(), current, run.getState(), current != run.getSettingsVersion(),
			Base64.getUrlEncoder().withoutPadding().encodeToString(run.getPayload()), "base64url",
			run.getPayloadFormat(),
			run.getPayloadHash(), run.getNonce(), run.getCredentialKeyId(), run.getProtocolVersion(),
			run.getCreatedAt(), run.getAckedAt(), clock.instant());
	}

	public record Created(SyncRunView run, boolean created) {
	}
}

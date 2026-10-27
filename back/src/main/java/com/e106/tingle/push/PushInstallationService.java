package com.e106.tingle.push;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.push.dto.PushAcknowledgement;
import com.e106.tingle.push.dto.RegisterPushRequest;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PushInstallationService {

	private final PushInstallationRepository installations;
	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final PushTokenEncryption encryption;
	private final Clock clock;
	private final EntityManager entityManager;

	@Transactional
	public PushAcknowledgement register(long userId, UUID familyId, RegisterPushRequest request) {
		lockActiveSession(userId, familyId);
		Instant now = clock.instant();
		String ciphertext = encryption.encrypt(request.installationId(), userId, request.token());
		if (installations.register(request.installationId(), userId, familyId,
			request.platform().name(), ciphertext, now, UUID.randomUUID()) != 1) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		PushInstallation saved = installations.findByClientInstallationIdAndUserId(request.installationId(), userId)
			.orElseThrow();
		entityManager.refresh(saved);
		return new PushAcknowledgement(now, saved.getBindingId());
	}

	@Transactional
	public void remove(long userId, UUID familyId, UUID installationId) {
		lockActiveSession(userId, familyId);
		if (installations.removeOwned(installationId, userId) != 1) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
	}

	private void lockActiveSession(long userId, UUID familyId) {
		users.findByIdForUpdate(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_REVOKED));
		// JWT 검사 후 로그아웃이 먼저 끝나도 회원 잠금 안에서 폐기 여부를 다시 검사한다.
		if (familyId == null || !sessions.isPushSessionUsable(familyId, userId, clock.instant())) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
	}
}

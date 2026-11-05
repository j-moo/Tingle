package com.e106.tingle.keyring;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.dto.Challenge;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RegistrationChallengeService {

	private final RegistrationChallengeRepository challenges;
	private final SecureRandom random = new SecureRandom();
	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final OnboardingService onboarding;
	private final Clock clock;
	@org.springframework.beans.factory.annotation.Value("${app.keyring-reset.enabled:false}")
	private boolean resetEnabled;

	@Transactional
	public Challenge issue(long userId, UUID familyId, UUID requestKey) {
		if (requestKey == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		users.findByIdForUpdate(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_REVOKED));
		Instant now = clock.instant();
		// 회원 잠금 안에서 로그아웃·세션 만료·필수 동의 철회를 재검사한다.
		if (familyId == null || !sessions.isPushSessionUsable(familyId, userId, now)) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
		if (!onboarding.canAccess(userId, OnboardingStatus.KEYRING_REQUIRED)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		// 회원 행 잠금이 같은 회원의 동시 발급을 직렬화한다. 재시도는 만료·소비 후에도 최초 결과다.
		RegistrationChallenge challenge = challenges.findByUserIdAndRequestKey(userId, requestKey)
			.orElseGet(() -> {
				byte[] bytes = new byte[32];
				random.nextBytes(bytes);
				String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
				return challenges.saveAndFlush(new RegistrationChallenge(UUID.randomUUID(), userId, requestKey,
					nonce, now, resetEnabled ? com.e106.tingle.keyring.proof.KeyringProofTranscript.PROTOCOL_VERSION_2
						: com.e106.tingle.keyring.proof.KeyringProofTranscript.PROTOCOL_VERSION));
			});
		return new Challenge(challenge.getChallengeId(), challenge.getNonce(), challenge.getExpiresAt(),
			challenge.getProtocolVersion());
	}
}

package com.e106.tingle.auth;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.e106.tingle.agreement.AgreementService;
import com.e106.tingle.auth.dto.TokenResponse;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 소셜 로그인, 토큰 재발급, 로그아웃. 약관 동의와 프로필은 각 도메인(agreement, user)이 맡는다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	// 이메일 수집 동의가 포함된 약관 항목 (app.agreement.items 의 type)
	private static final String EMAIL_CONSENT_TYPE = "PRIVACY";

	private final SocialVerifier socialVerifier;
	private final TokenService tokenService;
	private final UserRepository userRepository;
	private final AgreementService agreementService;
	private final OnboardingService onboardingService;
	private final Clock clock;

	/**
	 * 소셜 토큰을 검증하고 새 로그인 세션의 토큰을 발급한다. 처음 로그인한 소셜 계정이면 회원을 만든다.
	 * 이메일은 개인정보 수집에 동의한 회원만, 로그인할 때마다 소셜이 준 최신 값으로 바꾼다(소셜 제공을 철회했으면 지운다).
	 * 그래서 처음 가입한 회원의 이메일은 약관 동의 뒤 다음 로그인 때 저장된다.
	 * 소셜 서버 호출은 트랜잭션 밖에서 한다(외부 호출 동안 DB 연결을 잡고 있지 않기 위해).
	 */
	public TokenResponse login(String providerPath, String socialToken) {
		SocialProvider provider = SocialProvider.fromPath(providerPath);
		SocialVerifier.SocialAccount account = socialVerifier.verify(provider, socialToken);
		userRepository.insertIfAbsent(provider.name(), account.id(), clock.instant());
		User user = userRepository.findByProviderAndProviderSubject(provider, account.id()).orElseThrow();
		// 우리 서비스의 개인정보 수집 동의 전에는 이메일을 저장하지 않는다(소셜의 제공 동의만으로는 부족하다).
		if (agreementService.hasActiveConsent(user.getId(), EMAIL_CONSENT_TYPE)) {
			userRepository.updateEmail(user.getId(), account.email());
		}
		return toResponse(tokenService.issue(user.getId()));
	}

	// 트랜잭션을 걸지 않는다. 바깥 트랜잭션이 있으면 탈취 감지 시의 전체 폐기가 401 예외와 함께 롤백된다.
	public TokenResponse refresh(String refreshToken) {
		return toResponse(tokenService.rotate(refreshToken));
	}

	public void logout(String refreshToken) {
		tokenService.logout(refreshToken);
	}

	private TokenResponse toResponse(TokenService.IssuedTokens tokens) {
		return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer",
			tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt(), onboardingService.statusOf(tokens.userId()));
	}
}

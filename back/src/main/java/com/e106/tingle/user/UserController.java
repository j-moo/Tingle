package com.e106.tingle.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.dto.ContactEmailResponse;
import com.e106.tingle.user.dto.NicknameAvailabilityResponse;
import com.e106.tingle.user.dto.OnboardingDetailResponse;
import com.e106.tingle.user.dto.OnboardingResponse;
import com.e106.tingle.user.dto.ProfileResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 가입 단계 조회, 본인 프로필, 닉네임 확인. 경로는 팀 공유 인증 API 계약 를 따른다.
 * 경로의 {userId}가 로그인한 회원인지는 UserPathInterceptor 가 먼저 확인하므로 여기서는 토큰의 회원번호를 쓴다.
 */
@RestController
@RequiredArgsConstructor
public class UserController {

	private final OnboardingService onboardingService;
	private final ProfileService profileService;
	private final UnderAgeWithdrawalService underAgeWithdrawal;

	@GetMapping("/api/users/{userId}/onboarding")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public OnboardingDetailResponse onboarding(@AuthenticationPrincipal Jwt jwt) {
		return onboardingService.detail(userId(jwt));
	}

	// ponytail: 키링 등록 API(B 담당) 전이라 프로필은 약관 동의 직후부터 허용한다. 키링 API가 생기면 PROFILE_REQUIRED 로 되돌린다.
	@GetMapping("/api/users/{userId}/profile")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ProfileResponse getProfile(@AuthenticationPrincipal Jwt jwt) {
		return profileService.get(userId(jwt));
	}

	// 네이버 로그인 검수: 수집한 이메일을 본인에게 보여 주는 곳. 프로필 조회와 같은 단계부터 허용한다.
	@GetMapping("/api/users/{userId}/contact-email")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ContactEmailResponse getContactEmail(@AuthenticationPrincipal Jwt jwt) {
		return profileService.contactEmail(userId(jwt));
	}

	// 생략과 null 을 구분해야 해서(PATCH) 정해진 DTO 대신 JSON 그대로 받는다.
	// 만 19세 미만이면 저장은 롤백되고, 별도 트랜잭션에서 바로 탈퇴시킨 뒤 403 UNDER_AGE 를 그대로 돌려준다.
	@PatchMapping("/api/users/{userId}/profile")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ProfileResponse patchProfile(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
		ProfileResponse profile;
		try {
			profile = profileService.patch(userId(jwt), body);
		} catch (BusinessException e) {
			if (e.getErrorCode() == ErrorCode.UNDER_AGE) {
				underAgeWithdrawal.withdraw(userId(jwt));
			}
			throw e;
		}
		onboardingService.recordProgress(userId(jwt));
		return profile;
	}

	// 프로필 등록 완료(기능 명세 PRO-01). 다음 가입 단계를 돌려준다.
	@PostMapping("/api/users/{userId}/profile/complete")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public OnboardingResponse completeProfile(@AuthenticationPrincipal Jwt jwt) {
		profileService.register(userId(jwt));
		return new OnboardingResponse(onboardingService.recordProgress(userId(jwt)));
	}

	@GetMapping("/api/users/nickname-availability")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public NicknameAvailabilityResponse checkNickname(@AuthenticationPrincipal Jwt jwt,
		@RequestParam String nickname) {
		return profileService.checkNickname(userId(jwt), nickname);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}

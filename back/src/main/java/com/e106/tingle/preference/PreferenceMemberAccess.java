package com.e106.tingle.preference;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.ProfileRepository;

import lombok.RequiredArgsConstructor;

/**
 * A 담당 회원·가입 단계와의 연결 경계. 기존 검증을 재사용하며 공통 엔티티의 이관은151에서 수행한다.
 */
@Component
@RequiredArgsConstructor
public class PreferenceMemberAccess {

	private final ProfileRepository profiles;
	private final OnboardingService onboarding;

	public Gender requireGender(long userId) {
		if (!onboarding.canAccess(userId, OnboardingStatus.PREFERENCES_REQUIRED)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		Profile profile = profiles.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ONBOARDING_REQUIRED));
		if (!profile.isRegistered() || profile.getGender() == null) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		return profile.getGender();
	}
}

package com.e106.tingle.user;

/**
 * 가입 단계. 선언 순서가 진행 순서다(뒤로 갈수록 더 진행한 단계). 앱은 이 값으로 다음 화면을 정한다.
 */
public enum OnboardingStatus {
	AGREEMENT_REQUIRED,
	KEYRING_REQUIRED,
	PROFILE_REQUIRED,
	SURVEY_REQUIRED,
	PREFERENCES_REQUIRED,
	SYNC_PENDING,
	READY;

	public boolean isBefore(OnboardingStatus other) {
		return ordinal() < other.ordinal();
	}
}

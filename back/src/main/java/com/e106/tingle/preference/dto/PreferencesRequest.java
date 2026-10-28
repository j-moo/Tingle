package com.e106.tingle.preference.dto;

import com.e106.tingle.preference.AgePreferenceMode;

/**
 * 검증된 희망 조건 입력. 저장 모델·설정 버전·ETag 매핑은 별도 계약을 따른다.
 */
public record PreferencesRequest(
	AgePreferenceMode desiredAgeMode,
	Integer desiredAgeMin,
	Integer desiredAgeMax
) {
}

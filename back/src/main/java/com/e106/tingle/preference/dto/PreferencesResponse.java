package com.e106.tingle.preference.dto;

import java.util.List;

import com.e106.tingle.preference.AgePreferenceMode;
import com.e106.tingle.user.Gender;

/**
 * desiredGenders는 읽기 전용 이성 투영. 미선택 나이는 null로 유지한다.
 */
public record PreferencesResponse(
	List<Gender> desiredGenders,
	AgePreferenceMode desiredAgeMode,
	Integer desiredAgeMin,
	Integer desiredAgeMax
) {
}

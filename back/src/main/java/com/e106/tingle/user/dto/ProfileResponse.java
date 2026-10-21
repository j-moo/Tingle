package com.e106.tingle.user.dto;

import java.time.LocalDate;

import com.e106.tingle.user.DrinkingFrequency;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.Religion;
import com.e106.tingle.user.SmokingStatus;

/**
 * 본인 프로필 조회·저장 응답. 값이 없는 항목도 키를 빼지 않고 null 로 보낸다(통합 명세: 필수 키, null 허용).
 */
public record ProfileResponse(
	long userId,
	String nickname,
	Gender gender,
	LocalDate birthDate,
	SmokingStatus smokingStatus,
	DrinkingFrequency drinkingFrequency,
	Religion religion,
	String introduction,
	String imageUrl,
	boolean hasProfilePhoto,
	boolean completed
) {

	public static ProfileResponse from(Profile profile) {
		return new ProfileResponse(profile.getUserId(), profile.getNickname(), profile.getGender(),
			profile.getBirthDate(), profile.getSmokingStatus(), profile.getDrinkingFrequency(), profile.getReligion(),
			profile.getIntroduction(), profile.getImageUrl(), profile.getImageUrl() != null, profile.isCompleted());
	}

	public static ProfileResponse empty(long userId) {
		return new ProfileResponse(userId, null, null, null, null, null, null, null, null, false, false);
	}
}

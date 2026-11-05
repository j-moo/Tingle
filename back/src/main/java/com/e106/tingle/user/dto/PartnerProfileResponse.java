package com.e106.tingle.user.dto;

import com.e106.tingle.user.DrinkingFrequency;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.Religion;
import com.e106.tingle.user.SmokingStatus;

/**
 * 채팅 상대 프로필(기능 명세 PRO-03). 이름은 본인 프로필 응답과 같고, 회원 번호·생년월일·사진 주소는 보내지 않는다.
 * 사진은 hasProfilePhoto 로 있음만 알리고 파일은 GET /api/chat-rooms/{chatRoomId}/partner-photo 로 받는다.
 */
public record PartnerProfileResponse(
	String nickname,
	int age,
	Gender gender,
	SmokingStatus smokingStatus,
	DrinkingFrequency drinkingFrequency,
	Religion religion,
	String introduction,
	boolean hasProfilePhoto
) {
}

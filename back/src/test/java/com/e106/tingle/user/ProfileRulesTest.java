package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/**
 * 프로필 입력값 규칙 (팀 공유 인증 API 계약 "입력값 규칙").
 */
class ProfileRulesTest {

	@Test
	@DisplayName("닉네임은 앞뒤 공백만 지우고 내부 공백은 둔다. 한글·영문·숫자·공백 2~20자")
	void nicknameRules() {
		assertThat(ProfileRules.normalizeNickname("  가을  하늘 ")).isEqualTo("가을  하늘");
		assertThat(ProfileRules.isValidNickname("a".repeat(20))).isTrue();
		assertThat(ProfileRules.isValidNickname("a".repeat(21))).isFalse();
		assertThat(ProfileRules.isValidNickname("가")).isFalse();
		assertThat(ProfileRules.isValidNickname("   ")).isFalse();
		assertThat(ProfileRules.isValidNickname("가을\t하늘")).isFalse();
		assertThat(ProfileRules.isValidNickname("가을😀")).isFalse();
		assertThatThrownBy(() -> ProfileRules.normalizeNickname("a!"))
			.extracting(e -> ((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.INVALID_NICKNAME);
	}

	@Test
	@DisplayName("독립 자모는 단독 닉네임과 완성형·영문 혼합 닉네임 모두 거부한다")
	void nicknameRejectsStandaloneJamoAndMixedNames() {
		for (String nickname : new String[] {"ㄱㄴ", "ㅏㅓ", "홍ㄱ", "abcㅋ", "가ㅏ"}) {
			assertThat(ProfileRules.isValidNickname(nickname)).isFalse();
			assertErrorCode(() -> ProfileRules.normalizeNickname(nickname), ErrorCode.INVALID_NICKNAME);
		}
	}

	@Test
	@DisplayName("자모가 나뉘어 들어온 한글도 NFC로 합쳐서 검사·저장한다")
	void nicknameIsNfcNormalized() {
		String decomposed = "가을";
		assertThat(ProfileRules.normalizeNickname(decomposed)).isEqualTo("가을");
	}

	@Test
	@DisplayName("만 나이는 생일 당일부터 오르고, 2월 29일생은 평년에 3월 1일부터 오른다")
	void ageRules() {
		LocalDate birth = LocalDate.of(2000, 5, 10);
		assertThat(ProfileRules.age(birth, LocalDate.of(2019, 5, 9))).isEqualTo(18);
		assertThat(ProfileRules.age(birth, LocalDate.of(2019, 5, 10))).isEqualTo(19);

		LocalDate leapDay = LocalDate.of(2004, 2, 29);
		assertThat(ProfileRules.age(leapDay, LocalDate.of(2023, 2, 28))).isEqualTo(18);
		assertThat(ProfileRules.age(leapDay, LocalDate.of(2023, 3, 1))).isEqualTo(19);
		assertThat(ProfileRules.age(leapDay, LocalDate.of(2024, 2, 29))).isEqualTo(20);
	}

	@Test
	@DisplayName("미래 날짜는 INVALID_BIRTH_DATE, 만 19세 미만은 UNDER_AGE")
	void birthDateValidation() {
		LocalDate today = LocalDate.of(2026, 10, 21);
		assertErrorCode(() -> ProfileRules.validateBirthDate(today.plusDays(1), today), ErrorCode.INVALID_BIRTH_DATE);
		assertErrorCode(() -> ProfileRules.validateBirthDate(LocalDate.of(2007, 10, 22), today), ErrorCode.UNDER_AGE);
		ProfileRules.validateBirthDate(LocalDate.of(2007, 10, 21), today);
	}

	@Test
	@DisplayName("자기소개: 줄바꿈을 \\n 으로 맞추고, 공백뿐이면 null, 보이는 글자 200자·줄바꿈 3개까지")
	void introductionRules() {
		assertThat(ProfileRules.normalizeIntroduction("a\r\nb")).isEqualTo("a\nb");
		assertThat(ProfileRules.normalizeIntroduction(" \n\t ")).isNull();
		assertThat(ProfileRules.normalizeIntroduction("a\nb\nc\nd")).isEqualTo("a\nb\nc\nd");
		assertErrorCode(() -> ProfileRules.normalizeIntroduction("a\nb\nc\nd\ne"), ErrorCode.INVALID_INTRODUCTION);

		String family = "👨‍👩‍👧";
		assertThat(ProfileRules.visibleLength(family)).isEqualTo(1);
		assertThat(ProfileRules.normalizeIntroduction(family.repeat(200))).isNotNull();
		assertErrorCode(() -> ProfileRules.normalizeIntroduction("가".repeat(201)), ErrorCode.INVALID_INTRODUCTION);
	}

	private static void assertErrorCode(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run)
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(expected);
	}
}

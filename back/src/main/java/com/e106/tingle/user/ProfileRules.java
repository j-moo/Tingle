package com.e106.tingle.user;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import java.util.regex.Pattern;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/**
 * 프로필 입력값 규칙 (팀 공유 인증 API 계약 "입력값 규칙"). FE도 같은 규칙으로 검사한다.
 */
public final class ProfileRules {

	// 한글 완성형·영문·숫자·일반 공백(U+0020). 앞뒤 공백은 정규화에서 지우므로 공백만으로는 통과하지 못한다.
	private static final Pattern NICKNAME = Pattern.compile("^[가-힣a-zA-Z0-9 ]{2,20}$");
	private static final Pattern EDGE_SPACES = Pattern.compile("^ +| +$");
	private static final int ADULT_AGE = 19;
	private static final int MIN_BIRTH_YEAR = 1900;
	private static final int INTRODUCTION_MAX_LENGTH = 200;
	private static final int INTRODUCTION_MAX_LINE_BREAKS = 3;

	private ProfileRules() {
	}

	/**
	 * NFC 정규화하고 앞뒤 공백을 지운다. 내부 공백은 그대로 둔다. 규칙에 맞지 않으면 INVALID_NICKNAME.
	 */
	public static String normalizeNickname(String raw) {
		String nickname = clean(raw);
		if (!NICKNAME.matcher(nickname).matches()) {
			throw new BusinessException(ErrorCode.INVALID_NICKNAME);
		}
		return nickname;
	}

	public static boolean isValidNickname(String raw) {
		return NICKNAME.matcher(clean(raw)).matches();
	}

	// 형식 검사 없이 정규화만 한다 (중복 확인 응답에 보여 줄 값).
	public static String clean(String raw) {
		return EDGE_SPACES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("");
	}

	/**
	 * 중복 비교용 키. 닉네임 중복 확인은 항상 이 값으로 한다.
	 */
	public static String nicknameKey(String nickname) {
		return nickname.toLowerCase(Locale.ROOT);
	}

	/**
	 * 한국 날짜 기준 만 나이. 생일 당일부터 오른다.
	 * 2월 29일생은 평년에 3월 1일부터 오른다(Period 가 평년 2월 28일을 아직 생일 전으로 계산한다).
	 */
	public static int age(LocalDate birthDate, LocalDate today) {
		return Period.between(birthDate, today).getYears();
	}

	/**
	 * 미래 날짜·너무 이른 날짜는 INVALID_BIRTH_DATE, 만 19세 미만은 UNDER_AGE.
	 */
	public static void validateBirthDate(LocalDate birthDate, LocalDate today) {
		if (birthDate.isAfter(today) || birthDate.getYear() < MIN_BIRTH_YEAR) {
			throw new BusinessException(ErrorCode.INVALID_BIRTH_DATE);
		}
		if (age(birthDate, today) < ADULT_AGE) {
			throw new BusinessException(ErrorCode.UNDER_AGE);
		}
	}

	/**
	 * 줄바꿈을 \n 으로 맞추고, 공백·줄바꿈뿐이면 미입력(null)으로 본다.
	 * 보이는 글자 200자, 줄바꿈 3개를 넘으면 INVALID_INTRODUCTION.
	 */
	public static String normalizeIntroduction(String raw) {
		String text = raw.replace("\r\n", "\n").replace('\r', '\n');
		if (text.isBlank()) {
			return null;
		}
		long lineBreaks = text.chars().filter(c -> c == '\n').count();
		if (visibleLength(text) > INTRODUCTION_MAX_LENGTH || lineBreaks > INTRODUCTION_MAX_LINE_BREAKS) {
			throw new BusinessException(ErrorCode.INVALID_INTRODUCTION);
		}
		return text;
	}

	/**
	 * 화면에 보이는 글자 수(Unicode 확장 문자소). 가족 이모지처럼 여러 문자로 된 이모지도 1자다.
	 * FE의 Dart characters 패키지와 같은 기준이다.
	 */
	static int visibleLength(String text) {
		BreakIterator iterator = BreakIterator.getCharacterInstance();
		iterator.setText(text);
		int count = 0;
		while (iterator.next() != BreakIterator.DONE) {
			count++;
		}
		return count;
	}
}

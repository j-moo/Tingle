package com.e106.tingle.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class ErrorMessagesTest {

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	@DisplayName("모든 오류 코드에 표시 문구가 있고, 번역이 없는 언어로 요청해도 한국어 문구를 쓴다")
	void everyCodeHasMessageWithKoreanFallback() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);

		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.getMessage()).as(code.name()).isNotBlank().doesNotStartWith("error.");
		}
		assertThat(ErrorCode.AUTH_REQUIRED.getMessage()).isEqualTo("로그인이 필요합니다.");
	}
}

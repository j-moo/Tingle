package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.e106.tingle.global.error.BusinessException;

class ChatTextTest {
	@ParameterizedTest
	@ValueSource(strings = {"https://example.com/join?id=123#x", "www.example.com", "example.co.kr/path",
		"bit.ly/abcd", "https://예시.한국/경로", "예시.한국/경로", "http://192.0.2.1:8080/join", "192.0.2.1/a",
		"https://[2001:db8::1]:8080/join", "https://[2001:db8::1]"})
	void masksTheCompleteAddress(String address) {
		assertThat(ChatText.mask("여기 (" + address + "), 확인!"))
			.isEqualTo("여기 ([웹 주소 차단]), 확인!");
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://x.y/ 너를 죽일 거야", "x.com/　너를 죽일 거야",
		"www.example.com 너를 죽일 거야", "192.0.2.1/a 너를 죽일 거야", "https://x.com/''너를 죽일 거야"})
	void unicodeSpacesEndTheAddressSoTheTextAfterItIsKept(String text) {
		assertThat(ChatText.mask(text)).startsWith("[웹 주소 차단]").endsWith("너를 죽일 거야");
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://example.com/r?k=synthetic'opaque-demo", "www.example.com/a'b?c='d'",
		"https://example.com/r?k=synthetic''opaque-demo", "https://example.com/r?k=a'''b",
		"example.com/r?k=a'b", "192.0.2.1/r?k=a'b", "https://evil.com'a", "www.evil.com'a/path", "https://naver.com's",
		"https://a@evil.com/path", "https://evil.com\\path", "https://evil.com!x", "https://[2001:db8::1]'a",
		"999.1.1.1/https://evil.com", "999.1.1.1/www.evil.com"})
	void anApostropheOrAnOddHostCharacterDoesNotLeaveTheAddressUnmasked(String text) {
		assertThat(ChatText.mask(text)).isIn("[웹 주소 차단]", "[웹 주소 차단]'");
	}

	@Test
	void anAddressHiddenBehindAnInvalidHostIsStillMasked() {
		assertThat(ChatText.mask("999.1.1.1/evil.com")).isEqualTo("999.1.1.1/[웹 주소 차단]");
		assertThat(ChatText.mask("1.2.3.999?u=naver.com")).isEqualTo("1.2.3.999?u=[웹 주소 차단]");
		assertThat(ChatText.mask("버전 1.2.3.999/4.5")).isEqualTo("버전 1.2.3.999/4.5");
	}

	@Test
	void longAddressesDoNotExhaustTheRegexStack() {
		assertThat(ChatText.mask("https://x.y/" + "́".repeat(12000))).isEqualTo("[웹 주소 차단]");
		assertThat(ChatText.mask("https://x.y/" + "'a".repeat(20000))).isEqualTo("[웹 주소 차단]");
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://x.y/\"너를 죽일 거야\"", "x.com/'너를 죽일 거야'", "https://x.y/“너를 죽일 거야”",
		"www.example.com「너를 죽일 거야」", "https://x.y/„너를 죽일 거야“", "https://x.y/〝너를 죽일 거야〞"})
	void quotesEndTheAddressSoTheQuotedTextIsKept(String text) {
		assertThat(ChatText.mask(text)).startsWith("[웹 주소 차단]").contains("너를 죽일 거야");
	}

	@ParameterizedTest
	@ValueSource(strings = {"www.“naver”.com", "https://「naver」.com", "https://\"naver\".com"})
	void anAddressPrefixFollowedByAQuoteIsStillMasked(String text) {
		assertThat(ChatText.mask(text)).startsWith("[웹 주소 차단]").doesNotContain("www.", "https://");
	}

	@ParameterizedTest
	@ValueSource(strings = {"네이버에서 검색해", "버전 1.2", "10.21에 만나", "hello@example.com",
		"카톡 아이디는 tingle123", "example 닷 com"})
	void preservesExcludedOrdinaryText(String text) {
		assertThat(ChatText.mask(text)).isEqualTo(text);
	}

	@Test
	void validatesOriginalLengthBeforeMaskingAndKeepsExpandedResult() {
		String original = "x.co ".repeat(200);
		assertThat(ChatText.normalize(original)).isEqualTo(original);
		assertThat(ChatText.mask(original)).hasSizeGreaterThan(1000);
		assertThatThrownBy(() -> ChatText.normalize(original + "x")).isInstanceOf(BusinessException.class);
	}

	@Test
	void countsExtendedGraphemesAndNormalizesLineBreaksWithoutTruncating() {
		String family = "👨‍👩‍👧‍👦";
		assertThat(ChatText.normalize(family.repeat(1000))).isEqualTo(family.repeat(1000));
		assertThatThrownBy(() -> ChatText.normalize(family.repeat(1001))).isInstanceOf(BusinessException.class);
		assertThat(ChatText.normalize("첫째\r\n둘째\r셋째")).isEqualTo("첫째\n둘째\n셋째");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \n\t", "\u00a0\u3000"})
	void rejectsWhitespaceOnly(String text) {
		assertThatThrownBy(() -> ChatText.normalize(text)).isInstanceOf(BusinessException.class);
	}

	@Test
	void boundsPathologicalCombiningSequencesByUtf8Bytes() {
		assertThatThrownBy(() -> ChatText.normalize("a" + "\u0301".repeat(40000)))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsDatabaseControlCharactersAndMalformedSurrogates() {
		for (char value : new char[] {0, 1, Character.MIN_SURROGATE}) {
			assertThatThrownBy(() -> ChatText.normalize("text" + value)).isInstanceOf(BusinessException.class);
		}
	}
}

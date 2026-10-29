package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import tools.jackson.databind.ObjectMapper;

class IcebreakerQuestionsTest {
	private final IcebreakerQuestions questions = source("classpath:icebreakers/general-ko-v1.json");

	@Test
	void loadsTheApprovedGeneralQuestionsAndVersion() {
		IcebreakerGateway.Generated result = questions.fallback();
		assertThat(result.source()).isEqualTo("GENERAL_FALLBACK");
		assertThat(result.promptVersion()).isEqualTo("icebreakers-general-ko-v1");
		assertThat(result.questions()).containsExactly("요즘 즐겁게 하는 취미가 있나요?",
			"하루 중 가장 좋아하는 시간은 언제인가요?", "이번 주에 기대하는 작은 일이 있나요?");
	}

	@Test
	void missingGeneralQuestionsDoNotPretendToBeGenerated() {
		assertThat(source("classpath:icebreakers/missing.json").fallback()).isNull();
	}

	@Test
	void rejectsMissingSourceAndVersion() {
		assertThat(questions.validate(new IcebreakerGateway.Generated(List.of("하나", "둘", "셋"), null, "v1")))
			.isNull();
		assertThat(questions.validate(new IcebreakerGateway.Generated(List.of("하나", "둘", "셋"), "GENERATED", " ")))
			.isNull();
	}

	@Test
	void rejectsWrongQuestionCountAndBlankQuestions() {
		assertThat(questions.validate(generated(List.of("하나", "둘")))).isNull();
		assertThat(questions.validate(generated(List.of("하나", "둘", " ")))).isNull();
	}

	@Test
	void measuresUnicodeCodePointsRatherThanUtf16Units() {
		String question = "😀".repeat(200);
		assertThat(questions.validate(generated(List.of(question, "둘", "셋")))).isNotNull();
		assertThat(questions.validate(generated(List.of(question + "😀", "둘", "셋")))).isNull();
	}

	@Test
	void rejectsDuplicatesAfterWhitespaceAndCompatibilityNormalization() {
		assertThat(questions.validate(generated(List.of("ＡＢＣ", "abc", "셋")))).isNull();
		assertThat(questions.validate(generated(List.of("요즘 취미", "요즘  취미", "셋")))).isNull();
	}

	@Test
	void rejectsUrlsContactAndSensitiveInformation() {
		for (String unsafe : List.of("https://example.com 방문", "010-0000-0000", "종교가 같나요?", "test@example.com")) {
			assertThat(questions.validate(generated(List.of(unsafe, "둘", "셋")))).isNull();
		}
	}

	@Test
	void rejectsIpv4WebAddressesWithoutAScheme() {
		for (String unsafe : List.of("192.168.0.1에서 확인해 볼까요?", "8.8.8.8에 가 볼까요?",
			"127.0.0.1:8080/example에 접속해 볼까요?")) {
			assertThat(questions.validate(generated(List.of(unsafe, "둘", "셋")))).isNull();
		}
	}

	@Test
	void rejectsInternationalizedDomainsWithoutAScheme() {
		for (String unsafe : List.of("예시.한국에 가 볼까요?", "例え.テスト에서 확인해 볼까요?",
			"пример.рф를 함께 볼까요?")) {
			assertThat(questions.validate(generated(List.of(unsafe, "둘", "셋")))).isNull();
		}
	}

	@Test
	void acceptsOrdinaryKoreanQuestions() {
		List<String> values = List.of("요즘 즐겁게 하는 취미가 있나요?", "쉬는 날 해 보고 싶은 일은 무엇인가요?",
			"최근 기억에 남는 좋은 일은 무엇인가요?");
		assertThat(questions.validate(generated(values)).questions()).containsExactlyElementsOf(values);
	}

	@Test
	void trimsValidatedQuestionsWithoutChangingTheirMeaning() {
		assertThat(questions.validate(generated(List.of(" 하나 ", "둘", "셋"))).questions())
			.containsExactly("하나", "둘", "셋");
	}

	private IcebreakerGateway.Generated generated(List<String> values) {
		return new IcebreakerGateway.Generated(values, "GENERATED", "v1");
	}

	private IcebreakerQuestions source(String location) {
		return new IcebreakerQuestions(new DefaultResourceLoader(), new ObjectMapper(),
			new IcebreakerProperties(false, "", "", "", location));
	}
}

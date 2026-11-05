package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 설문 파일(SIS-Q-1.1-100)의 네 가지 응답 규칙으로 검사한다.
 * Q001: 1~5 점수 또는 unknown / Q040: 보기 1~2개 배열 또는 unknown
 * Q061: 보기 하나·unknown·any / Q075: 보기 1~5개 배열·unknown·any
 */
class SurveyAnswerValidatorTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static SurveyDefinition definition;

	@BeforeAll
	static void loadSurvey() throws Exception {
		definition = new SurveyCatalog(MAPPER, "SIS-Q-1.1-100").current();
	}

	private static JsonNode json(String value) {
		return MAPPER.readTree(value);
	}

	private static void assertValid(String questionId, String answer) {
		assertThatCode(() -> SurveyAnswerValidator.validate(definition, questionId, json(answer)))
			.doesNotThrowAnyException();
	}

	private static void assertError(String questionId, String answer, ErrorCode expected) {
		assertThatThrownBy(() -> SurveyAnswerValidator.validate(definition, questionId, json(answer)))
			.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	@ParameterizedTest
	@ValueSource(strings = {"1", "3", "5", "4.0", "\"unknown\""})
	@DisplayName("점수 문항은 1~5와 unknown 을 받는다. 숫자는 값으로 비교해 4.0 도 4 다")
	void scoreAcceptsRangeAndUnknown(String answer) {
		assertValid("Q001", answer);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "6", "4.5", "\"4\"", "\"any\"", "[3]", "1e999", "-1e999"})
	@DisplayName("점수 문항은 범위 밖·소수·문자열 숫자·any·배열이면 422 INVALID_ANSWER")
	void scoreRejectsOthers(String answer) {
		assertError("Q001", answer, ErrorCode.INVALID_ANSWER);
	}

	@ParameterizedTest
	@ValueSource(strings = {"[\"rest\"]", "[\"talk\",\"walk\"]", "\"unknown\""})
	@DisplayName("복수 선택 문항은 보기 1~2개 배열이나 unknown 을 받는다")
	void multiChoiceAccepts(String answer) {
		assertValid("Q040", answer);
	}

	@ParameterizedTest
	@ValueSource(strings = {"[]", "[\"rest\",\"talk\",\"walk\"]", "[\"rest\",\"rest\"]", "[\"sleep\"]",
		"\"rest\"", "\"any\"", "[\"unknown\"]"})
	@DisplayName("복수 선택 문항은 빈 배열·선택 수 초과·중복·없는 보기·단일 값·any·배열 안 unknown 이면 422")
	void multiChoiceRejects(String answer) {
		assertError("Q040", answer, ErrorCode.INVALID_ANSWER);
	}

	@Test
	@DisplayName("any 는 파일이 허용한 문항에서만 단독 값으로 맞는다")
	void anyOnlyWhereAllowed() {
		assertValid("Q061", "\"any\"");
		assertValid("Q061", "\"mid\"");
		assertValid("Q075", "\"any\"");
		assertValid("Q075", "[\"rest\",\"talk\",\"culture\",\"walk\",\"active\"]");
		assertError("Q075", "[\"any\"]", ErrorCode.INVALID_ANSWER);
		assertError("Q061", "\"none\"", ErrorCode.INVALID_ANSWER);
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "true", "{}", "\"\"", "\"  \"", "[[\"rest\"]]", "[\"rest\",null]", "[{}]"})
	@DisplayName("숫자·문자열·그 배열이 아닌 답은 형식 오류 400 INVALID_REQUEST")
	void malformedAnswerIsBadRequest(String answer) {
		assertError("Q040", answer, ErrorCode.INVALID_REQUEST);
	}

	@Test
	@DisplayName("설문 파일에 없는 문항 ID 는 404 RESOURCE_NOT_FOUND")
	void unknownQuestionIsNotFound() {
		assertError("Q999", "1", ErrorCode.RESOURCE_NOT_FOUND);
		assertError("", "1", ErrorCode.RESOURCE_NOT_FOUND);
	}

	@Test
	@DisplayName("100문항 모두 지원하는 규칙만 쓰고, 파일의 보기 값은 그 문항에서 맞는 답이다")
	void everyQuestionUsesSupportedRules() {
		for (JsonNode question : definition.questions()) {
			String id = question.get("questionId").asString();
			JsonNode schema = question.get("responseSchema");
			boolean multi = schema.toString().contains("\"array\"");
			for (JsonNode option : question.path("options")) {
				JsonNode answer = multi ? MAPPER.createArrayNode().add(option.get("value")) : option.get("value");
				assertThatCode(() -> SurveyAnswerValidator.validate(definition, id, answer))
					.as(id).doesNotThrowAnyException();
			}
			assertThat(SurveyAnswerValidator.matches(schema, json("\"unknown\"")))
				.as(id).isEqualTo(question.path("allowUnknown").asBoolean());
			assertThat(SurveyAnswerValidator.matches(schema, json("\"any\"")))
				.as(id).isEqualTo(question.path("allowAny").asBoolean());
		}
	}

	@Test
	@DisplayName("지원하지 않는 규칙이 들어간 파일은 조용히 통과시키지 않는다")
	void unsupportedRuleFailsLoudly() {
		assertThatThrownBy(() -> SurveyAnswerValidator.matches(json("{\"pattern\":\"^a$\"}"), json("\"a\"")))
			.isInstanceOf(IllegalStateException.class);
	}
}

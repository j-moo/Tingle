package com.e106.tingle.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

import tools.jackson.databind.json.JsonMapper;

/**
 * 설문 정의 회귀 사례(ANSWER_FIXTURES)가 여러 개 고르는 문항과 답 저장 한도를 맞게 다루는지.
 */
class RuleDocumentsTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	@DisplayName("최소 2개를 골라야 하는 문항은 앱이 보기 하나([값])를 보낼 수 없어 거절한다")
	void minItemsTwoRejected() {
		assertThat(RuleDocuments.fixtures(definition("\"a\",\"b\",\"c\"", 2, 3)).failures())
			.anyMatch(failure -> failure.contains("\"a\" expected=accept actual=reject"));
	}

	@Test
	@DisplayName("배열 분기 하나인 문항은 보기 하나를 [값] 으로 받으면 통과한다")
	void singleArrayBranchAcceptsOneChoice() {
		assertThat(RuleDocuments.fixtures(definition("\"a\",\"b\",\"c\"", 1, 3)).failures()).isEmpty();
	}

	@Test
	@DisplayName("받는 답 가운데 가장 긴 조합이 저장 한도(100자)를 넘으면 실패로 남긴다")
	void longestAnswerMustFit() {
		String first = "\"" + "a".repeat(60) + "\"";
		String second = "\"" + "b".repeat(60) + "\"";
		assertThat(RuleDocuments.fixtures(definition(first + "," + second, 1, 2)).failures())
			.anyMatch(failure -> failure.contains("too-long"));
	}

	@Test
	@DisplayName("가장 긴 조합은 저장 한도와 같은 코드 포인트로 고른다(이모지 보기가 섞여도 놓치지 않음)")
	void longestAnswerCountsCodePoints() {
		String emoji = "\"" + "😀".repeat(30) + "\"";
		String first = "\"" + "a".repeat(50) + "\"";
		String second = "\"" + "b".repeat(50) + "\"";
		assertThat(RuleDocuments.fixtures(definition(emoji + "," + first + "," + second, 1, 2)).failures())
			.anyMatch(failure -> failure.contains("too-long"));
	}

	@Test
	@DisplayName("응답 규칙이 보기에 없는 값을 받거나 중복 선택·최대 선택 수 없는 배열이면 거절한다")
	void schemaMustMatchOptions() {
		String hidden = json("\"a\"", "\"a\",\"hidden\"", true, 2);
		assertThat(RuleDocuments.fixtures(SurveyDefinition.of(MAPPER.readTree(hidden), "h")).failures())
			.anyMatch(failure -> failure.contains("\"hidden\" expected=option"));
		String repeatable = json("\"a\"", "\"a\"", false, 30);
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(repeatable)))
			.contains("document.questions[0].responseSchema");
	}

	@Test
	@DisplayName("배열 분기는 items.enum 으로 항목 허용값을 정해야 한다")
	void arrayItemsRequired() {
		String noItems = json("\"a\",\"b\"", "\"a\",\"b\"", true, 2).replace("\"items\":{\"enum\":[\"a\",\"b\"]},", "");
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(noItems)))
			.contains("document.questions[0].responseSchema");
	}

	@Test
	@DisplayName("최소 2개 문항에서 unknown 을 항목으로 받으면, 정상 보기로 개수를 채운 거절 사례에서 걸린다")
	void rejectedValueCheckedWithMinItems() {
		String json = json("\"a\",\"b\"", "\"a\",\"b\",\"unknown\"", true, 2)
			.replace("\"minItems\":1", "\"minItems\":2");
		assertThat(RuleDocuments.fixtures(SurveyDefinition.of(MAPPER.readTree(json), "h")).failures())
			.anyMatch(failure -> failure.contains("\"unknown\" expected=reject actual=accept"))
			.anyMatch(failure -> failure.contains("[\"unknown\"] expected=reject"));
	}

	@Test
	@DisplayName("unknown 을 보기·배열 항목에 넣어도 배열 안 unknown 은 거절해야 한다")
	void specialAnswerNeverInArray() {
		String json = json("\"a\",\"unknown\"", "\"a\",\"unknown\"", true, 2)
			.replace("\"allowUnknown\":false", "\"allowUnknown\":true");
		assertThat(RuleDocuments.fixtures(SurveyDefinition.of(MAPPER.readTree(json), "h")).failures())
			.anyMatch(failure -> failure.contains("[\"unknown\"] expected=reject"));
	}

	@Test
	@DisplayName("배열 분기가 둘이면 앱이 보기 하나를 값 그대로 보내므로 받지 못해 거절한다")
	void separateArrayBranches() {
		String branch = "{\"type\":\"array\",\"items\":{\"enum\":[%s]},\"uniqueItems\":true,\"minItems\":2,"
			+ "\"maxItems\":2}";
		String json = json("\"a\",\"b\",\"c\",\"d\"", "\"a\"", true, 2)
			.replaceFirst("\"responseSchema\":\\{.*\\}\\}\\]\\}$", "\"responseSchema\":{\"oneOf\":["
				+ branch.formatted("\"a\",\"b\"") + "," + branch.formatted("\"c\",\"d\"") + "]}}]}");
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(json))).isEmpty();
		assertThat(RuleDocuments.fixtures(SurveyDefinition.of(MAPPER.readTree(json), "h")).failures())
			.anyMatch(failure -> failure.contains("\"c\" expected=accept actual=reject"));
	}

	@Test
	@DisplayName("배열 분기가 겹쳐도 분기에서 나올 수 있는 가장 긴 답이 저장 한도를 넘으면 실패로 남긴다")
	void overlappingBranchesStorageBound() {
		String first = "\"" + "a".repeat(58) + "\"";
		String second = "\"" + "b".repeat(57) + "\"";
		String third = "\"" + "c".repeat(56) + "\"";
		String all = first + "," + second + "," + third;
		String wide = "{\"type\":\"array\",\"items\":{\"enum\":[" + all + "]},\"uniqueItems\":true,\"minItems\":1,"
			+ "\"maxItems\":2}";
		String pair = "{\"type\":\"array\",\"items\":{\"enum\":[" + first + "," + second + "]},\"uniqueItems\":true,"
			+ "\"minItems\":2,\"maxItems\":2}";
		String json = json(all, first, true, 2).replaceFirst("\"responseSchema\":\\{.*\\}\\}\\]\\}$",
			"\"responseSchema\":{\"oneOf\":[" + wide + "," + pair + "]}}]}");
		assertThat(RuleDocuments.fixtures(SurveyDefinition.of(MAPPER.readTree(json), "h")).failures())
			.anyMatch(failure -> failure.contains("too-long"));
	}

	@Test
	@DisplayName("앱이 여는 조건: 표시 순서 1부터 빈틈없음·보기 값 중복 없음·배열 분기는 type:array")
	void appStructure() {
		String gap = json("\"a\"", "\"a\"", true, 1).replace("\"ordinal\":1", "\"ordinal\":2");
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(gap)))
			.contains("document.questions.ordinal");
		String duplicated = json("\"a\",\"a\"", "\"a\"", true, 1);
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(duplicated)))
			.contains("document.questions[0].options[1]");
		String sameNumber = json("1,1.0", "1", true, 1);
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(sameNumber)))
			.contains("document.questions[0].options[1]");
		String noType = json("\"a\"", "\"a\"", true, 1).replace("\"type\":\"array\",", "");
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(noType)))
			.contains("document.questions[0].responseSchema");
	}

	@Test
	@DisplayName("설문 버전은 회차 저장 한도와 같은 30자를 넘을 수 없다")
	void surveyVersionMax30() {
		String version = "V".repeat(31);
		String json = json("\"a\"", "\"a\"", true, 1).replace("\"surveyVersion\":\"T\"",
			"\"surveyVersion\":\"" + version + "\"");
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, version, MAPPER.readTree(json)))
			.contains("document.surveyVersion");
	}

	private static SurveyDefinition definition(String values, int minItems, int maxItems) {
		String json = json(values, values, true, maxItems).replace("\"minItems\":1", "\"minItems\":" + minItems);
		assertThat(RuleDocuments.problems(RuleKind.QUESTIONNAIRE, "T", MAPPER.readTree(json))).isEmpty();
		return SurveyDefinition.of(MAPPER.readTree(json), "h");
	}

	// 여러 개 고르는 문항 하나짜리 설문. optionValues 는 보기, enumValues 는 응답 규칙이 받는 값.
	private static String json(String optionValues, String enumValues, boolean unique, int maxItems) {
		StringBuilder options = new StringBuilder();
		for (String value : optionValues.split(",")) {
			options.append(options.isEmpty() ? "" : ",").append("{\"value\":").append(value)
				.append(",\"label\":\"x\"}");
		}
		return "{\"surveyVersion\":\"T\",\"wordingVersion\":\"W\",\"questionCount\":1,\"questions\":[{"
			+ "\"questionId\":\"Q1\",\"ordinal\":1,\"part\":1,\"text\":\"t\",\"allowUnknown\":false,\"allowAny\":false,"
			+ "\"calculationBinding\":\"E\",\"options\":[" + options + "],\"responseSchema\":{\"type\":\"array\","
			+ "\"items\":{\"enum\":[" + enumValues + "]},\"uniqueItems\":" + unique + ",\"minItems\":1"
			+ ",\"maxItems\":" + maxItems + "}}]}";
	}
}

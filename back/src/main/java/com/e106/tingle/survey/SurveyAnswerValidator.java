package com.e106.tingle.survey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

import tools.jackson.databind.JsonNode;

/**
 * 문항 하나의 답을 회차에 고정된 설문 파일의 responseSchema 로 검사한다. 값은 바꾸지 않는다.
 * 지원 규칙: oneOf(정확히 하나만 맞아야 함)·enum·const·type:array·items·minItems·maxItems·uniqueItems.
 * 문항 수·보기·unknown/any 허용 여부는 코드에 두지 않고 파일에서 읽는다("any"는 파일이 허용한 문항에서만 맞는다).
 * 숫자와 문자열은 서로 바꾸지 않는다(4 와 "4" 는 다른 값). 숫자끼리는 값으로 비교한다(4 와 4.0 은 같은 값).
 */
public final class SurveyAnswerValidator {

	private static final Set<String> SUPPORTED = Set.of(
		"oneOf", "enum", "const", "type", "items", "minItems", "maxItems", "uniqueItems");

	private SurveyAnswerValidator() {
	}

	/**
	 * @throws BusinessException 없는 문항 404 RESOURCE_NOT_FOUND, 답 형식(숫자·문자열·그 배열이 아님) 400 INVALID_REQUEST,
	 *     규칙 위반 422 INVALID_ANSWER
	 */
	public static void validate(SurveyDefinition definition, String questionId, JsonNode answer) {
		JsonNode question = definition.questions().stream()
			.filter(q -> q.path("questionId").asString("").equals(questionId))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		requireWellFormed(answer);
		if (!matches(question.path("responseSchema"), answer)) {
			throw new BusinessException(ErrorCode.INVALID_ANSWER);
		}
	}

	// 답은 숫자, 빈 값이 아닌 문자열, 또는 그 둘로만 된 배열이어야 한다. 그 밖(null·불리언·객체·중첩 배열)은 형식 오류다.
	private static void requireWellFormed(JsonNode answer) {
		if (answer == null || !(isScalar(answer)
			|| (answer.isArray() && answer.valueStream().allMatch(SurveyAnswerValidator::isScalar)))) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	private static boolean isScalar(JsonNode value) {
		return value.isNumber() || (value.isString() && !value.asString().isBlank());
	}

	static boolean matches(JsonNode schema, JsonNode value) {
		if (!schema.isObject() || schema.isEmpty()) {
			throw new IllegalStateException("설문 파일의 responseSchema 가 비어 있습니다");
		}
		for (String keyword : schema.propertyNames()) {
			if (!SUPPORTED.contains(keyword)) {
				throw new IllegalStateException("지원하지 않는 설문 응답 규칙입니다: " + keyword);
			}
		}
		if (schema.has("oneOf")) {
			long matched = schema.get("oneOf").valueStream().filter(branch -> matches(branch, value)).count();
			if (matched != 1) {
				return false;
			}
		}
		if (schema.has("const") && !same(schema.get("const"), value)) {
			return false;
		}
		if (schema.has("enum") && schema.get("enum").valueStream().noneMatch(option -> same(option, value))) {
			return false;
		}
		if (schema.has("type") && !"array".equals(schema.get("type").asString())) {
			throw new IllegalStateException("지원하지 않는 설문 응답 타입입니다: " + schema.get("type"));
		}
		if (schema.has("type") || schema.has("items") || schema.has("minItems") || schema.has("maxItems")
			|| schema.has("uniqueItems")) {
			return value.isArray() && matchesArray(schema, value);
		}
		return true;
	}

	private static boolean matchesArray(JsonNode schema, JsonNode array) {
		int size = array.size();
		if ((schema.has("minItems") && size < schema.get("minItems").asInt())
			|| (schema.has("maxItems") && size > schema.get("maxItems").asInt())) {
			return false;
		}
		List<JsonNode> seen = new ArrayList<>();
		for (JsonNode item : array) {
			if (schema.has("items") && !matches(schema.get("items"), item)) {
				return false;
			}
			if (schema.path("uniqueItems").asBoolean(false) && seen.stream().anyMatch(prev -> same(prev, item))) {
				return false;
			}
			seen.add(item);
		}
		return true;
	}

	// 같은 종류끼리만 비교한다. 숫자는 값(4 = 4.0), 문자열은 그대로.
	// 1e999 처럼 무한대로 읽히는 숫자는 어떤 보기와도 같지 않다(decimalValue 변환 예외 방지).
	private static boolean same(JsonNode expected, JsonNode actual) {
		if (expected.isNumber() && actual.isNumber()) {
			if (!Double.isFinite(expected.doubleValue()) || !Double.isFinite(actual.doubleValue())) {
				return false;
			}
			return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
		}
		if (expected.isString() && actual.isString()) {
			return expected.asString().equals(actual.asString());
		}
		return false;
	}
}

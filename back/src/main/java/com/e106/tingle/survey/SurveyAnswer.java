package com.e106.tingle.survey;

import java.io.Serializable;
import java.time.Instant;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한 회차의 문항별 답. 문자열 "4"와 숫자 4를 타입 증거 없이 서로 바꾸지 않는다(answer_type 으로 구분).
 * 여러 개 고르는 문항의 배열 답은 JSON 글자 그대로(예: ["rest","talk"]) 저장하고 ARRAY 로 표시한다.
 */
@Entity
@Table(name = "survey_answer")
@IdClass(SurveyAnswer.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyAnswer {

	@Id
	private Long surveyId;
	@Id
	@Column(length = 50)
	private String questionId;
	@Column(nullable = false, length = 100)
	private String answerValue;
	@Column(columnDefinition = "text")
	private String answerType;
	// 구 aggregate JSON에는 문항별 시각이 없었다. 그 자료는 NULL로 보존한다.
	private Instant answeredAt;

	static SurveyAnswer of(long surveyId, String questionId, JsonNode value, Instant answeredAt) {
		if (surveyId <= 0 || questionId == null || questionId.isBlank() || questionId.length() > 50 || value == null) {
			throw new IllegalArgumentException("회차·문항·답이 필요합니다");
		}
		SurveyAnswer answer = new SurveyAnswer();
		answer.surveyId = surveyId;
		answer.questionId = questionId;
		if (value.isString()) {
			answer.answerType = "STRING";
			answer.answerValue = value.asString();
		} else if (value.isNumber()) {
			// 4 와 4.0 은 같은 보기이므로 짧은 표기(4)로 저장한다.
			answer.answerType = "NUMBER";
			answer.answerValue = value.decimalValue().stripTrailingZeros().toPlainString();
		} else if (value.isBoolean() || value.isNull()) {
			answer.answerType = value.isBoolean() ? "BOOLEAN" : "NULL";
			answer.answerValue = value.toString();
		} else if (value.isArray() && value.valueStream().allMatch(item -> item.isString() || item.isNumber())) {
			// 검증기가 받는 배열(문자열·숫자 항목)은 모두 저장할 수 있어야 한다(설문 파일이 숫자 배열 문항을 둘 경우 대비).
			answer.answerType = "ARRAY";
			answer.answerValue = value.toString();
		} else {
			throw new IllegalArgumentException("저장할 수 없는 답 형식입니다");
		}
		if (answer.answerValue.codePointCount(0, answer.answerValue.length()) > 100) {
			throw new IllegalArgumentException("답값이 저장 한도를 넘습니다");
		}
		answer.answeredAt = answeredAt;
		return answer;
	}

	// 같은 문항을 다시 저장하면 값·형식·시각을 새 답으로 바꾼다(회차 행 잠금 안에서만 부른다).
	void overwrite(SurveyAnswer newer) {
		answerValue = newer.answerValue;
		answerType = newer.answerType;
		answeredAt = newer.answeredAt;
	}

	public JsonNode value(JsonMapper mapper) {
		if ("STRING".equals(answerType)) {
			return mapper.readTree(mapper.writeValueAsString(answerValue));
		}
		if (!"NUMBER".equals(answerType) && !"BOOLEAN".equals(answerType) && !"NULL".equals(answerType)
			&& !"ARRAY".equals(answerType)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		JsonNode value = mapper.readTree(answerValue);
		if (("NUMBER".equals(answerType) && value.isNumber()) || ("ARRAY".equals(answerType) && value.isArray())
			|| ("BOOLEAN".equals(answerType) && value.isBoolean()) || ("NULL".equals(answerType) && value.isNull())) {
			return value;
		}
		throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	public static class Key implements Serializable {
		private Long surveyId;
		private String questionId;
	}
}

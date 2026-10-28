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
 * 한 회차의 문항별 scalar 답. 문자열 "4"와 숫자 4를 타입 증거 없이 서로 바꾸지 않는다.
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
			throw new IllegalArgumentException("회차·문항·scalar 답이 필요합니다");
		}
		SurveyAnswer answer = new SurveyAnswer();
		answer.surveyId = surveyId;
		answer.questionId = questionId;
		if (value.isString()) {
			answer.answerType = "STRING";
			answer.answerValue = value.asString();
		} else if (value.isNumber() || value.isBoolean() || value.isNull()) {
			answer.answerType = value.isNumber() ? "NUMBER" : value.isBoolean() ? "BOOLEAN" : "NULL";
			answer.answerValue = value.toString();
		} else {
			throw new IllegalArgumentException("복합 답값을 scalar로 추정 변환하지 않습니다");
		}
		if (answer.answerValue.codePointCount(0, answer.answerValue.length()) > 100) {
			throw new IllegalArgumentException("답값이 저장 한도를 넘습니다");
		}
		answer.answeredAt = answeredAt;
		return answer;
	}

	JsonNode value(JsonMapper mapper) {
		if ("STRING".equals(answerType)) {
			return mapper.readTree(mapper.writeValueAsString(answerValue));
		}
		if (!"NUMBER".equals(answerType) && !"BOOLEAN".equals(answerType) && !"NULL".equals(answerType)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		JsonNode value = mapper.readTree(answerValue);
		if (("NUMBER".equals(answerType) && value.isNumber())
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

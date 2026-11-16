package com.e106.tingle.risk;

import java.util.Map;

import com.e106.tingle.risk.RiskAnalysisTypes.Input;

import tools.jackson.databind.json.JsonMapper;

/**
 * 토큰 수의 상한을 센다. AI 서버가 토큰을 세는 문자열({"contextBundle":[...]}, 공백 없음, 한글 그대로)과
 * 같은 길이의 JSON을 만들어 UTF-8 바이트 수에 특수 토큰 수를 더한다(2026-11-12 AI 파트와 합의).
 * 실제 토큰 수는 AI 서버가 승인된 토크나이저로 다시 센다. 여기서는 3,000 한도를 넘길 입력을 미리 줄이는 데만 쓴다.
 * ponytail: 토큰 1개가 1바이트 이상이라는 가정의 상한이라 한글이 많으면 실제보다 2~3배 크게 잡힌다(문맥이 더 일찍 잘린다).
 * 문맥을 한도까지 꽉 채워야 하면 승인된 tokenizer.json 을 읽는 구현으로 바꾼다.
 */
public final class ByteBoundRiskTokenizer implements RiskTokenizer {

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final int specialTokens;

	public ByteBoundRiskTokenizer(int specialTokens) {
		// 한도(3,000)에 비해 큰 값을 넣으면 모든 입력이 한도 초과로 건너뛰어지므로 설정 실수를 기동 때 막는다.
		if (specialTokens < 0 || specialTokens > 100) {
			throw new IllegalArgumentException("special token count must be between 0 and 100");
		}
		this.specialTokens = specialTokens;
	}

	@Override
	public int applyAsInt(Input input) {
		return Math.addExact(mapper.writeValueAsBytes(Map.of("contextBundle", input.messages())).length, specialTokens);
	}
}

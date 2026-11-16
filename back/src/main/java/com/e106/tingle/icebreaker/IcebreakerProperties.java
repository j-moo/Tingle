package com.e106.tingle.icebreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 켜고 끄는 스위치는 없다. 릴리스 파일 위치(manifestLocation)가 있으면 AI 시작 질문을 요청한다.
@ConfigurationProperties("app.icebreaker")
public record IcebreakerProperties(@DefaultValue("") String endpoint, @DefaultValue("") String serviceToken,
	@DefaultValue("") String manifestLocation,
	@DefaultValue("classpath:icebreakers/general-ko-v1.json") String generalQuestionsLocation) {
}

package com.e106.tingle.icebreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.icebreaker")
public record IcebreakerProperties(@DefaultValue("false") boolean externalEnabled,
	@DefaultValue("") String endpoint, @DefaultValue("") String serviceToken,
	@DefaultValue("") String manifestLocation,
	@DefaultValue("classpath:icebreakers/general-ko-v1.json") String generalQuestionsLocation) {
}

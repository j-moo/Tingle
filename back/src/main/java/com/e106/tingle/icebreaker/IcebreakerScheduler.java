package com.e106.tingle.icebreaker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.icebreaker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class IcebreakerScheduler {
	private final IcebreakerProcessor processor;

	@Scheduled(fixedDelayString = "${app.icebreaker.poll-ms:5000}")
	public void process() {
		processor.runOnce();
	}
}

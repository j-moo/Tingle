package com.e106.tingle.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.async-observation.enabled", havingValue = "true", matchIfMissing = true)
public class AsyncJobObservationSchedule {
	private final AsyncJobObserver observer;

	@Scheduled(fixedDelayString = "${app.async-observation.poll-ms:60000}",
		initialDelayString = "${app.async-observation.poll-ms:60000}")
	public void observe() {
		observer.publish();
	}
}

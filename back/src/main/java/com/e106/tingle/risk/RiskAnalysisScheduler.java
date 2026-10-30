package com.e106.tingle.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"app.risk.external-enabled", "app.risk.scheduling-enabled"}, havingValue = "true")
public class RiskAnalysisScheduler {

	private final RiskAnalysisDispatcher dispatcher;

	@Scheduled(fixedDelayString = "${app.risk.poll-ms:5000}")
	public void process() {
		dispatcher.runOnce();
	}
}

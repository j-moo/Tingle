package com.e106.tingle.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
// 개발·운영은 늘 켠다. 통합 테스트만 끈다(application-test.yml). Release 가 없으면 매 주기 아무것도 보내지 않고 끝난다.
@ConditionalOnProperty(name = "app.risk.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class RiskAnalysisScheduler {

	private final RiskAnalysisDispatcher dispatcher;

	@Scheduled(fixedDelayString = "${app.risk.poll-ms:5000}")
	public void process() {
		dispatcher.runOnce();
	}
}

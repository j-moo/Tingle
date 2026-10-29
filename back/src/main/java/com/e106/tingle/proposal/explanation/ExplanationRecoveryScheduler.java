package com.e106.tingle.proposal.explanation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 설명 전용 복구 빈. 기본 60초 검사 주기는 최초 접수로부터 5분인 실패 기한과 별개다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.match-explanations.scheduling-enabled", havingValue = "true",
	matchIfMissing = true)
public class ExplanationRecoveryScheduler {
	private final ExplanationRecovery recovery;

	public ExplanationRecoveryScheduler(ExplanationRecovery recovery) {
		this.recovery = recovery;
	}

	@Scheduled(fixedDelayString = "${app.match-explanations.recovery-poll-ms:60000}",
		initialDelayString = "${app.match-explanations.recovery-poll-ms:60000}")
	public void closeOverdueRuns() {
		recovery.afterRestart();
	}
}

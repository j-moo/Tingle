package com.e106.tingle.personality;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

/**
 * 새 요청·멈춘 실행·재시도 대기를 주기적으로 처리한다. 테스트는 꺼 두고 PersonalAnalysisWorker 를 직접 부른다.
 * ponytail: 요청 직후 바로 깨우지 않고 poll-ms 마다 확인한다. 결과 대기 시간이 문제면 커밋 후 실행을 추가한다.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tingle.ai.personal-analysis.scheduling-enabled", havingValue = "true",
	matchIfMissing = true)
class PersonalAnalysisScheduler {

	private final PersonalAnalysisWorker worker;

	@Scheduled(fixedDelayString = "${tingle.ai.personal-analysis.poll-ms:2000}")
	void run() {
		worker.runDue();
	}
}

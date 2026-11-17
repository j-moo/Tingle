package com.e106.tingle.encounter;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import lombok.extern.slf4j.Slf4j;

/** 만남 평가 예약 실행을 채팅 안내 설정과 독립적으로 활성화한다. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EncounterEvaluationSchedulingConfiguration {

	/** 즉시 평가가 DB 연결을 다 차지하지 않게 동시 실행 수를 막는다. 자리가 없으면 주기 확인이 집는다. */
	private final Semaphore slots = new Semaphore(4);

	/**
	 * 접수가 커밋되면 그 만남을 바로 평가해 제안까지 잇는다(주기 확인을 기다리지 않는다).
	 * 접수 요청의 응답을 늦추지 않게 다른 스레드에서 돌리고, 실패하면 주기 확인이 다시 집는다.
	 */
	@Autowired
	void wakeOnEnqueue(EncounterEvaluationWorker worker) {
		worker.wakeAfterEnqueue(matchingId -> {
			if (!slots.tryAcquire()) {
				return;
			}
			Thread.ofVirtual().start(() -> {
				try {
					worker.run(matchingId);
				} catch (RuntimeException exception) {
					log.warn("만남 평가 작업을 처리하지 못했습니다. matchingId={}", matchingId);
				} finally {
					slots.release();
				}
			});
		});
	}
}

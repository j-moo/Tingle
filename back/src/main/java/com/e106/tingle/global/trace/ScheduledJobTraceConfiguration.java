package com.e106.tingle.global.trace;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 예약 작업(@Scheduled)은 실행할 때마다 새 추적 번호를 MDC에 넣는다.
 * 한 번의 실행에서 나온 로그(작업 경고, 스프링의 예약 작업 오류 로그, AI 호출 실패)를 같은 번호로 묶어 본다.
 */
@Configuration(proxyBeanMethods = false)
public class ScheduledJobTraceConfiguration {

	@Bean
	ThreadPoolTaskSchedulerCustomizer scheduledJobTrace() {
		return scheduler -> scheduler.setTaskDecorator(task -> () -> {
			MDC.put(RequestIdFilter.MDC_KEY, UUID.randomUUID().toString());
			try {
				task.run();
			} finally {
				MDC.remove(RequestIdFilter.MDC_KEY);
			}
		});
	}
}

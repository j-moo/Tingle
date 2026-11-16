package com.e106.tingle.chat.notice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
// 종료 1시간 전 안내는 항상 동작한다. 이 설정은 자동 테스트에서 주기 실행을 멈추는 용도로만 쓴다.
@ConditionalOnProperty(name = "app.chat-notices.scheduling-enabled", havingValue = "true")
public class NoticeScheduler {
	private final NoticeDispatcher dispatcher;

	@Scheduled(fixedDelayString = "${app.chat-notices.poll-ms:15000}")
	public void dispatch() {
		dispatcher.runDue();
	}
}

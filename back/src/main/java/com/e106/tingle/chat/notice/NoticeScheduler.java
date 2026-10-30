package com.e106.tingle.chat.notice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"app.chat-notices.end-notice-enabled", "app.chat-notices.scheduling-enabled"},
	havingValue = "true")
public class NoticeScheduler {
	private final NoticeDispatcher dispatcher;

	@Scheduled(fixedDelayString = "${app.chat-notices.poll-ms:15000}")
	public void dispatch() {
		dispatcher.runDue();
	}
}

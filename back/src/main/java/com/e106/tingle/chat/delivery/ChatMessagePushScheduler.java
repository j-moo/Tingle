package com.e106.tingle.chat.delivery;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chat-message-push.scheduling-enabled", havingValue = "true")
public class ChatMessagePushScheduler {
	private final ChatMessagePushDispatcher dispatcher;
	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	private final AtomicBoolean running = new AtomicBoolean();

	@Scheduled(fixedDelayString = "${app.chat-message-push.poll-ms:1000}")
	public void dispatch() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			worker.execute(() -> {
				try {
					dispatcher.runDue();
				} finally {
					running.set(false);
				}
			});
		} catch (RejectedExecutionException exception) {
			running.set(false);
		}
	}

	@PreDestroy
	public void stop() {
		worker.shutdownNow();
	}
}

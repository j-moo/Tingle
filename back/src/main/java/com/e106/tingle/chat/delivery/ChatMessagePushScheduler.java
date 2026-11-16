package com.e106.tingle.chat.delivery;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PreDestroy;

@Configuration
@EnableScheduling
public class ChatMessagePushScheduler {
	private final ChatMessagePushDispatcher dispatcher;
	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	private final AtomicBoolean running = new AtomicBoolean();

	public ChatMessagePushScheduler(ChatMessagePushDispatcher dispatcher,
		@Value("${app.chat-message-push.poll-ms:}") String pollMs) {
		if (!pollMs.matches("[1-9][0-9]{0,8}")) {
			throw new IllegalStateException("CHAT_MESSAGE_PUSH_POLL_MS 값이 없거나 올바르지 않습니다 (1 이상 ms)");
		}
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${app.chat-message-push.poll-ms}")
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

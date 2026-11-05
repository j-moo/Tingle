package com.e106.tingle.chat.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ChatMessagePushSchedulerTest {
	@Test
	void blockedProviderDoesNotBlockTheSharedSchedulerOrStartOverlappingWorkers() throws Exception {
		ChatMessagePushDispatcher dispatcher = mock(ChatMessagePushDispatcher.class);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(dispatcher.runDue()).willAnswer(invocation -> {
			started.countDown();
			release.await(5, TimeUnit.SECONDS);
			return 1;
		});
		ChatMessagePushScheduler scheduler = new ChatMessagePushScheduler(dispatcher);
		try {
			assertTimeoutPreemptively(Duration.ofSeconds(2), scheduler::dispatch);
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
			assertTimeoutPreemptively(Duration.ofSeconds(2), scheduler::dispatch);
			then(dispatcher).should(times(1)).runDue();
		} finally {
			release.countDown();
			scheduler.stop();
		}
	}
}

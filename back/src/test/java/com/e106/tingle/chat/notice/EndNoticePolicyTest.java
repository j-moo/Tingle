package com.e106.tingle.chat.notice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EndNoticePolicyTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(EndNoticePolicy.class, NoticeScheduler.class)
		.withBean(NoticeDispatcher.class, () -> mock(NoticeDispatcher.class));

	@Test
	void schedulingFlagAloneCannotEnableTheMvpExcludedNotice() {
		context.withPropertyValues("app.chat-notices.scheduling-enabled=true").run(result -> {
			assertThat(result.getBean(EndNoticePolicy.class).enabled()).isFalse();
			assertThat(result).doesNotHaveBean(NoticeScheduler.class);
		});
	}

	@Test
	void explicitFutureOptInDoesNotIgnoreDisabledScheduling() {
		context.withPropertyValues("app.chat-notices.end-notice-enabled=true",
			"app.chat-notices.scheduling-enabled=false").run(result -> {
				assertThat(result.getBean(EndNoticePolicy.class).enabled()).isTrue();
				assertThat(result).doesNotHaveBean(NoticeScheduler.class);
			});
	}
}

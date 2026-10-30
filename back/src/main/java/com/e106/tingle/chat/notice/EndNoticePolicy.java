package com.e106.tingle.chat.notice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EndNoticePolicy {
	private final boolean enabled;

	public EndNoticePolicy(@Value("${app.chat-notices.end-notice-enabled:false}") boolean enabled) {
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}
}

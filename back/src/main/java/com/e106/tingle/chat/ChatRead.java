package com.e106.tingle.chat;

import java.time.Instant;

import lombok.Getter;

/** Read receipt projection stored in the owning participant's read_state, not a separate table. */
@Getter
public class ChatRead {
	private final Long messageId;
	private final Long userId;
	private final Instant readAt;

	public ChatRead(long messageId, long userId, Instant now) {
		this.messageId = messageId;
		this.userId = userId;
		this.readAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
	}
}

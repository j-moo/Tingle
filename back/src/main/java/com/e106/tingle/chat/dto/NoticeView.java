package com.e106.tingle.chat.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record NoticeView(UUID noticeId, String type, Instant scheduledAt, Instant expiresAt,
	String text, Instant acknowledgedAt) {
}

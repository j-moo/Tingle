package com.e106.tingle.chat.dto;

import java.time.Instant;
import java.util.List;

public record ReadMessagesResult(List<Long> messageIds, Instant serverTime) {
}

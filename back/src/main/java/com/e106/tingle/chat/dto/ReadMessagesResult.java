package com.e106.tingle.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReadMessagesResult(List<UUID> messageIds, Instant serverTime) {
}

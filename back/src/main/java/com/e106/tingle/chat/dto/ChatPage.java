package com.e106.tingle.chat.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChatPage(List<ChatRoomView> items, String nextCursor, Instant serverTime) {
}

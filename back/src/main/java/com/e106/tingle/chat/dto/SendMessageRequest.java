package com.e106.tingle.chat.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record SendMessageRequest(@NotNull UUID clientMessageId, @NotNull String text) {
}

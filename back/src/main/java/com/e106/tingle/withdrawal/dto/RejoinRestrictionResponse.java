package com.e106.tingle.withdrawal.dto;

import java.time.Instant;

public record RejoinRestrictionResponse(String code, String message, Instant rejoinAvailableAt) {
}

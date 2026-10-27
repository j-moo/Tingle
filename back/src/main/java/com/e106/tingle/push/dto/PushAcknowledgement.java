package com.e106.tingle.push.dto;

import java.time.Instant;
import java.util.UUID;

public record PushAcknowledgement(Instant serverTime, UUID bindingId) {
}

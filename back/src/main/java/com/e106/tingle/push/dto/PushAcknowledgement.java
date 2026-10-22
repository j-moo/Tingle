package com.e106.tingle.push.dto;

import java.time.Instant;

public record PushAcknowledgement(Instant serverTime) {
}

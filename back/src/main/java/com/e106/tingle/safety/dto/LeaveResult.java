package com.e106.tingle.safety.dto;

import java.time.Instant;

public record LeaveResult(long chatRoomId, Instant leftAt, boolean rematchingRestricted) {
}

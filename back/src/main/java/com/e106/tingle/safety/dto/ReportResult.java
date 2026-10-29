package com.e106.tingle.safety.dto;

import java.time.Instant;

public record ReportResult(long reportId, String status, Instant createdAt) {
}

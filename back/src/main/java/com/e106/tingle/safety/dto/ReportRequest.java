package com.e106.tingle.safety.dto;

import java.util.List;

import com.e106.tingle.report.ChatReport.ChatReportReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReportRequest(
	@NotNull @Positive Long chatRoomId,
	@NotNull ChatReportReason reason,
	List<@NotNull @Positive Long> messageIds,
	@Size(max = 1000) String detail
) {
}

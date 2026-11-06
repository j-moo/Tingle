package com.e106.tingle.support.lost.dto;

import java.time.Instant;
import java.util.List;

public record LostCasePage(List<Item> items, Long nextCursor) {

	public record Item(long caseId, String status, long deviceId, Instant createdAt, Instant replyDueAt) {
	}
}

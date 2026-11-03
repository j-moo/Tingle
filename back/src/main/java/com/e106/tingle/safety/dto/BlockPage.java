package com.e106.tingle.safety.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BlockPage(List<Item> items, String nextCursor) {
	public record Item(UUID blockId, String nickname, Instant createdAt) {
	}
}

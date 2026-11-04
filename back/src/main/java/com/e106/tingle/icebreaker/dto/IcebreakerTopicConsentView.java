package com.e106.tingle.icebreaker.dto;

import java.time.Instant;

public record IcebreakerTopicConsentView(boolean enabled, String noticeVersion, Instant grantedAt) {
}

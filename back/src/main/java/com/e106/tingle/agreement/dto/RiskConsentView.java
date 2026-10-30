package com.e106.tingle.agreement.dto;

import java.time.Instant;

public record RiskConsentView(boolean enabled, String noticeVersion, Instant grantedAt) {
}

package com.e106.tingle.risk.dto;

import java.time.Instant;

/** 표시 확인 결과. 이미 확인한 안내면 처음 확인한 시각을 그대로 돌려준다. */
public record SafetyNoticeDisplayed(long noticeId, Instant displayedAt) {
}

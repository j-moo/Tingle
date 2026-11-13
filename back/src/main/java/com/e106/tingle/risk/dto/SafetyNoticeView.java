package com.e106.tingle.risk.dto;

import java.time.Instant;

/** 나에게만 보이는 AI 위험 안내 한 건. 안내 문구는 앱이 유형(type)·대상(audience)으로 고른다. */
public record SafetyNoticeView(long noticeId, String type, String audience, Instant createdAt) {
}

package com.e106.tingle.agreement.dto;

import java.time.Instant;

/**
 * @param version       현재 동의 문서 버전
 * @param agreed        지금 동의한 상태인지
 * @param agreedVersion 동의한 문서 버전(동의 상태가 아니면 null)
 * @param recordedAt    마지막으로 동의·거절·철회한 시각(기록이 없으면 null)
 */
public record OptionalConsentView(String type, String version, boolean agreed, String agreedVersion,
	Instant recordedAt) {
}

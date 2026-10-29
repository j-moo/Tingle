package com.e106.tingle.settings.snapshot;

import java.time.Instant;

/** 내부 입력 증거. 소유권·입력 의미·규칙 파일 검증은 호출하는 서비스에서 수행한다. */
public record SettingsSnapshotView(long userId, long settingsVersion, String inputFormat,
	String inputJson, String inputHash, Instant capturedAt) {
}

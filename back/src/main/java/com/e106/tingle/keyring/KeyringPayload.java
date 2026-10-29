package com.e106.tingle.keyring;

import java.util.UUID;

/** 공식 기기 규격으로 인코딩한 서버 바이트다. 내부 입력 스냅샷을 대신 전달하지 않는다. */
public record KeyringPayload(long userId, long deviceId, UUID ownerGeneration, long settingsVersion,
	String format, byte[] bytes) {
	public KeyringPayload {
		if (userId <= 0 || deviceId <= 0 || ownerGeneration == null || settingsVersion <= 0
			|| format == null || !format.matches("[A-Za-z0-9._-]{1,64}") || bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("Invalid device payload");
		}
		bytes = bytes.clone();
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}

	@Override
	public String toString() {
		return "KeyringPayload[format=" + format + ", bytes=REDACTED]";
	}
}

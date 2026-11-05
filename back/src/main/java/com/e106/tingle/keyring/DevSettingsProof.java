package com.e106.tingle.keyring;

import java.time.Instant;

/** HTTP 요청 키별로 고정한 서버 증명. 다른 기기 challenge는 새 요청 키로 발급한다. */
public record DevSettingsProof(String protocolVersion, String keyId, String deviceNonce,
	Instant serverTime, String signature) {
	@Override
	public String toString() {
		return "DevSettingsProof[REDACTED]";
	}
}

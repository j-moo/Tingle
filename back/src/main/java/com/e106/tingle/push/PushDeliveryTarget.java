package com.e106.tingle.push;

import java.util.UUID;

/**
 * 발송 직전 해석한 내부 전달 정보. HTTP DTO·작업 payload·로그에 저장하지 않는다.
 */
public final class PushDeliveryTarget {

	private final UUID installationId;
	private final PushPlatform platform;
	private final String token;

	PushDeliveryTarget(UUID installationId, PushPlatform platform, String token) {
		this.installationId = installationId;
		this.platform = platform;
		this.token = token;
	}

	public UUID installationId() {
		return installationId;
	}

	public PushPlatform platform() {
		return platform;
	}

	public String token() {
		return token;
	}

	@Override
	public String toString() {
		return "PushDeliveryTarget[installationId=" + installationId
			+ ", platform=" + platform + ", token=[REDACTED]]";
	}
}

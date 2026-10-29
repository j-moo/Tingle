package com.e106.tingle.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 승인된 실제 릴리스 descriptor가 없으면 빈 설정으로 위험 분석을 승인하지 않는다. */
@ConfigurationProperties("app.risk.release")
public record RiskReleaseProperties(String descriptorFile) {

	public RiskReleaseProperties {
		descriptorFile = descriptorFile == null ? "" : descriptorFile;
	}
}

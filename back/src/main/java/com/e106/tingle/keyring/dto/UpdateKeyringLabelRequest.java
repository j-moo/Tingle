package com.e106.tingle.keyring.dto;

/** 키링 별명 변경. null·빈 문자열·공백만이면 별명을 지운다. */
public record UpdateKeyringLabelRequest(String label) {

	@Override
	public String toString() {
		return "UpdateKeyringLabelRequest[label=REDACTED]";
	}
}

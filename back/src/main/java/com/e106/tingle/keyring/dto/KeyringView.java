package com.e106.tingle.keyring.dto;

import com.e106.tingle.keyring.KeyringStatus;

/**
 * 내 키링 한 대. deviceSerial 은 BLE GET_DEVICE_INFO 로 읽는 일련번호와 같은 값이다(등록 때 앱이 보낸 값으로 저장).
 * 앱은 연결한 기기의 일련번호가 내 목록에 없으면 남의 키링으로 보고 연결을 끊는다.
 * label 은 주인이 붙인 별명이고, 없으면 일련번호 앞 10자다.
 */
public record KeyringView(long deviceId, String deviceSerial, KeyringStatus status, String firmwareVersion,
	String label) {

	private static final int DEFAULT_LABEL_LENGTH = 10;

	/** 저장된 별명이 없으면 일련번호 앞 10자(코드 포인트 기준)를 이름으로 쓴다. */
	public static String displayLabel(String label, String serial) {
		if (label != null) {
			return label;
		}
		int end = serial.offsetByCodePoints(0, Math.min(DEFAULT_LABEL_LENGTH, serial.codePointCount(0,
			serial.length())));
		return serial.substring(0, end);
	}

	/** 일련번호는 로그에 남기지 않는다(RegisterKeyringRequest 와 같은 방침). 기본 별명이 일련번호 앞부분이라 별명도 가린다. */
	@Override
	public String toString() {
		return "KeyringView[deviceId=" + deviceId + ", deviceSerial=REDACTED, status=" + status
			+ ", firmwareVersion=" + firmwareVersion + ", label=REDACTED]";
	}
}

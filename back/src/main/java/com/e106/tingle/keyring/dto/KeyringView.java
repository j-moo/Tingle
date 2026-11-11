package com.e106.tingle.keyring.dto;

import com.e106.tingle.keyring.KeyringStatus;

/**
 * 내 키링 한 대. deviceSerial 은 BLE GET_DEVICE_INFO 로 읽는 일련번호와 같은 값이다(등록 때 앱이 보낸 값으로 저장).
 * 앱은 연결한 기기의 일련번호가 내 목록에 없으면 남의 키링으로 보고 연결을 끊는다.
 */
public record KeyringView(long deviceId, String deviceSerial, KeyringStatus status, String firmwareVersion) {

	/** 일련번호는 로그에 남기지 않는다(RegisterKeyringRequest 와 같은 방침). */
	@Override
	public String toString() {
		return "KeyringView[deviceId=" + deviceId + ", deviceSerial=REDACTED, status=" + status
			+ ", firmwareVersion=" + firmwareVersion + "]";
	}
}

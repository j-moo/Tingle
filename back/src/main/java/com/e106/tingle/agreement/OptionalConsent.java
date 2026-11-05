package com.e106.tingle.agreement;

/**
 * 선택 정보별 동의 항목(app.agreement.items 의 required: false 항목과 같은 이름).
 * 동의가 없으면 그 정보를 저장하지 않고, 철회하면 저장한 값을 지운다.
 */
public enum OptionalConsent {
	PROFILE_PHOTO,
	PROFILE_INTRODUCTION,
	PROFILE_RELIGION
}

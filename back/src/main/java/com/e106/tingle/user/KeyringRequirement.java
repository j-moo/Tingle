package com.e106.tingle.user;

/** 가입 단계 응답의 키링 요구. 서버는 늘 REQUIRED 를 준다. OPTIONAL 은 앱 응답 계약 호환용으로만 남긴다. */
public enum KeyringRequirement {
	REQUIRED, OPTIONAL
}

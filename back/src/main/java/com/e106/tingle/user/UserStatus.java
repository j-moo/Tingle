package com.e106.tingle.user;

/**
 * 계정 상태 (ERD app_users.status). 정지·탈퇴 처리는 해당 기능을 만들 때 쓴다. 지금은 모두 ACTIVE 로 만든다.
 */
public enum UserStatus {
	ACTIVE,
	SUSPENDED,
	WITHDRAWN
}

package com.e106.tingle.user;

/**
 * 계정 상태 (ERD users.status). 기존 정지·탈퇴 대기·탈퇴 자료를 보존하며 ACTIVE만 접근을 허용한다.
 */
public enum UserStatus {
	ACTIVE,
	SUSPENDED,
	WITHDRAWAL_PENDING,
	WITHDRAWN
}

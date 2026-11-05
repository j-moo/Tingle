package com.e106.tingle.encounter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 만남 접수에서도 회원 잠금 뒤 현재 기기 소유를 검사한다. */
@Repository
@RequiredArgsConstructor
public class EncounterAccessRepository {
	private final JdbcTemplate jdbc;

	public void requireActiveMember(long userId) {
		var members = jdbc.query("select signup_completed_at is not null from users "
			+ "where id=? and status='ACTIVE' for update", (result, row) -> result.getBoolean(1), userId);
		if (members.isEmpty()) {
			throw new BusinessException(ErrorCode.AUTH_REQUIRED);
		}
		if (!members.getFirst()) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
	}

	public void requireOwnedRegisteredDevice(long userId, long deviceId) {
		var devices = jdbc.query("select id from keyring where id=? and owner_user_id=? "
			+ "and state='REGISTERED' for update", (result, row) -> result.getLong(1), deviceId, userId);
		if (devices.isEmpty()) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
	}
}

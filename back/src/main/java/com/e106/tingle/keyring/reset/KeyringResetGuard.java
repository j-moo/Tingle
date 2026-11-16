package com.e106.tingle.keyring.reset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class KeyringResetGuard {
	private final JdbcTemplate jdbc;

	/** Called under the same device row lock used by reset creation. */
	public void requireNoPendingReset(long deviceId) {
		if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from keyring_reset_run "
			+ "where keyring_id=? and completed_at is null)", Boolean.class, deviceId))) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
	}
}

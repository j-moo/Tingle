package com.e106.tingle.user;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.dto.WithdrawalEligibilityResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WithdrawalEligibilityService {

	private final UserRepository users;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	/** Registration and reset creation take this same member lock before changing ownership. */
	@Transactional
	public WithdrawalEligibilityResponse check(long userId) {
		User user = users.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
		// An inconsistent state must not make an owned device disappear from the withdrawal check.
		long registered = jdbc.queryForObject("select count(*) from keyring where owner_user_id=?",
			Long.class, userId);
		long pending = pendingResets(userId);
		List<String> blockers = new ArrayList<>();
		if (registered > 0) {
			blockers.add("WITHDRAWAL_KEYRING_REGISTERED");
		}
		if (pending > 0) {
			blockers.add("WITHDRAWAL_RESET_PENDING");
		}
		return new WithdrawalEligibilityResponse(blockers.isEmpty(), registered, pending,
			List.copyOf(blockers), clock.instant());
	}

	private long pendingResets(long userId) {
		// The reset execution table is optional on installations that have never enabled reset.
		// Once it exists, unfinished work blocks withdrawal even if the feature has since been disabled.
		boolean tableExists = Boolean.TRUE.equals(jdbc.queryForObject(
			"select to_regclass('keyring_reset_run') is not null", Boolean.class));
		if (!tableExists) {
			return 0;
		}
		// Command expiry permits reissuance. It is not proof of a completed device reset.
		return jdbc.queryForObject("select count(*) from keyring_reset_run "
			+ "where owner_user_id=? and completed_at is null", Long.class, userId);
	}
}

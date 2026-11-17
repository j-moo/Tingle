package com.e106.tingle.withdrawal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.MemberWithdrawalCleanup;
import com.e106.tingle.user.PendingEmails;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;
import com.e106.tingle.user.WithdrawalEligibilityService;
import com.e106.tingle.withdrawal.dto.WithdrawalPrepared;
import com.e106.tingle.withdrawal.dto.WithdrawalView;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {
	private final JdbcTemplate jdbc;
	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final WithdrawalIdentityService identities;
	private final WithdrawalReceiptTokens receipts;
	private final WithdrawalEligibilityService eligibility;
	private final MemberWithdrawalCleanup cleanup;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final PendingEmails pendingEmails;

	public WithdrawalPrepared prepare(long userId, UUID session, UUID requestKey, boolean lost) {
		return transaction.execute(status -> {
			User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
			identities.lockIdentity(user);
			users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
			requireSession(userId, session);
			Instant now = clock.instant();
			jdbc.update("delete from withdrawal_operation where user_id=? and expires_at<=?", userId,
				Timestamp.from(now));
			List<Operation> existing = jdbc.query("select * from withdrawal_operation where user_id=?",
				(row, index) -> read(row), userId);
			String kind = lost ? "LOST" : "NORMAL";
			Operation operation;
			if (!existing.isEmpty()) {
				operation = existing.getFirst();
				if (!kind.equals(operation.kind())) {
					throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				if (!session.equals(operation.session())) {
					// A fresh authenticated session may recover its own unexecuted request.
					// Lost-case final confirmation is separately bound to its reauthentication session.
					jdbc.update("update withdrawal_operation set session_id=?,request_key=? where id=?",
						session, requestKey, operation.id());
					operation = find(operation.id(), false);
				} else if (!requestKey.equals(operation.requestKey())) {
					throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
			} else {
				UUID id = UUID.randomUUID();
				// Verify key configuration before persisting a receipt the caller cannot recover.
				receipts.issue(id);
				Instant expires = now.plus(lost ? Duration.ofDays(30) : Duration.ofMinutes(30));
				jdbc.update("insert into withdrawal_operation(id,user_id,session_id,request_key,kind,prepared_at,"
					+ "expires_at,device_permanently_blocked) values(?,?,?,?,?,?,?,false)",
					id, userId, session, requestKey, kind, Timestamp.from(now), Timestamp.from(expires));
				operation = find(id, false);
			}
			return new WithdrawalPrepared(receipts.issue(operation.id()), view(operation));
		});
	}

	public WithdrawalView result(UUID id, String receiptToken) {
		receipts.verify(id, receiptToken);
		Operation operation = find(id, false);
		requireUnexpired(operation);
		return view(operation);
	}

	public void cancelPrepared(long userId, UUID session, UUID id) {
		transaction.executeWithoutResult(status -> {
			User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
			identities.lockIdentity(user);
			users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
			requireSession(userId, session);
			jdbc.update("delete from withdrawal_operation where id=? and user_id=? and completed_at is null",
				id, userId);
		});
	}

	/** The caller has checked operator authority and holds the target member row lock. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void discardPreparedUnderMemberLock(long userId, UUID id) {
		jdbc.update("delete from withdrawal_operation where id=? and user_id=? and completed_at is null", id, userId);
	}

	public WithdrawalView execute(long userId, UUID session, UUID id, String receiptToken) {
		receipts.verify(id, receiptToken);
		return executeRetried(userId, session, id, null, null, null);
	}

	/** Only the operator lost-device workflow may supply the targeted block action. */
	public WithdrawalView executeForOperator(long userId, UUID id, long operator, UUID operatorSession,
		Runnable authorizeAndBlock) {
		return executeRetried(userId, null, id, operator, operatorSession, authorizeAndBlock);
	}

	private WithdrawalView executeRetried(long userId, UUID session, UUID id, Long operator,
		UUID operatorSession, Runnable authorizeAndBlock) {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				return transaction.execute(status -> executeOnce(userId, session, id, operator, operatorSession,
					authorizeAndBlock));
			} catch (RelatedMembersChanged ignored) {
				// Recompute peers in a new transaction before taking any member locks again.
			}
		}
		throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	private WithdrawalView executeOnce(long userId, UUID session, UUID id, Long operator,
		UUID operatorSession, Runnable authorizeAndBlock) {
		Operation preliminary = find(id, false);
		requireUnexpired(preliminary);
		if (preliminary.completed() != null) {
			if (operator != null) {
				requireOperator(operator, operatorSession);
			}
			return view(preliminary);
		}
		if (!Objects.equals(preliminary.userId(), userId)
			|| (operator == null && (!Objects.equals(preliminary.session(), session)
				|| !"NORMAL".equals(preliminary.kind())))
			|| (operator != null && !"LOST".equals(preliminary.kind()))) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		identities.lockIdentity(user);
		List<Long> members = new ArrayList<>(cleanup.relatedMemberIds(userId));
		members.add(userId);
		if (operator != null) {
			members.add(operator);
		}
		List<Long> locked = members.stream().distinct().sorted().toList();
		for (long member : locked) {
			jdbc.queryForList("select id from users where id=? for update", Long.class, member);
		}
		if (!locked.containsAll(cleanup.relatedMemberIds(userId))) {
			throw new RelatedMembersChanged();
		}
		Operation operation = find(id, true);
		if (operation.completed() != null) {
			return view(operation);
		}
		requireUnexpired(operation);
		if (operator == null) {
			requireSession(userId, session);
		} else {
			requireOperator(operator, operatorSession);
			Objects.requireNonNull(authorizeAndBlock).run();
		}
		var checked = eligibility.check(userId);
		if (!checked.eligible()) {
			throw new BusinessException(ErrorCode.valueOf(checked.blockingCodes().getFirst()));
		}
		Instant completed = clock.instant();
		Instant rejoin = identities.onWithdrawal(user, completed);
		jdbc.update("insert into withdrawal_photo_task(id,withdrawal_id,file_key,attempts,next_attempt_at) "
			+ "select ?,?,photo_url,0,? from profile where user_id=? and photo_url is not null",
			UUID.randomUUID(), id, Timestamp.from(completed), userId);
		cleanup.cleanupUnderLocks(userId, completed);
		pendingEmails.forgetAfterCommit(userId);
		jdbc.update("update withdrawal_operation set user_id=null,session_id=null,request_key=null,completed_at=?,"
			+ "rejoin_available_at=?,expires_at=?,device_permanently_blocked=? where id=?",
			Timestamp.from(completed), Timestamp.from(rejoin), Timestamp.from(completed.plus(Duration.ofHours(72))),
			operator != null, id);
		return view(find(id, false));
	}

	private void requireSession(long userId, UUID session) {
		if (session == null || !sessions.isSessionActive(session, userId)) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
	}

	private void requireOperator(long operator, UUID session) {
		requireSession(operator, session);
		if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users "
			+ "where id=? and status='ACTIVE' and is_operator)", Boolean.class, operator))) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	private void requireUnexpired(Operation operation) {
		if (!clock.instant().isBefore(operation.expires())) {
			throw new BusinessException(ErrorCode.WITHDRAWAL_OPERATION_EXPIRED);
		}
	}

	private Operation find(UUID id, boolean lock) {
		return jdbc.query("select * from withdrawal_operation where id=?" + (lock ? " for update" : ""),
			(row, index) -> read(row), id).stream().findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private WithdrawalView view(Operation operation) {
		boolean pending = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 "
			+ "from withdrawal_photo_task where withdrawal_id=?)", Boolean.class, operation.id()));
		return new WithdrawalView(operation.id(), operation.completed() == null ? "PREPARED" : "COMPLETED",
			operation.prepared(), operation.expires(), operation.completed(), operation.rejoin(),
			operation.completed() == null ? "NOT_STARTED" : pending ? "PENDING" : "COMPLETED", operation.blocked());
	}

	private static Operation read(ResultSet row) throws SQLException {
		Timestamp completed = row.getTimestamp("completed_at");
		Timestamp rejoin = row.getTimestamp("rejoin_available_at");
		return new Operation(row.getObject("id", UUID.class), row.getObject("user_id", Long.class),
			row.getObject("session_id", UUID.class), row.getObject("request_key", UUID.class), row.getString("kind"),
			row.getTimestamp("prepared_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
			completed == null ? null : completed.toInstant(), rejoin == null ? null : rejoin.toInstant(),
			row.getBoolean("device_permanently_blocked"));
	}

	private record Operation(UUID id, Long userId, UUID session, UUID requestKey, String kind, Instant prepared,
		Instant expires, Instant completed, Instant rejoin, boolean blocked) {
	}

	private static class RelatedMembersChanged extends RuntimeException {
	}
}

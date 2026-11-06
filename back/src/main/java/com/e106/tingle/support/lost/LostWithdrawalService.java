package com.e106.tingle.support.lost;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.auth.SocialVerifier;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.i18n.Messages;
import com.e106.tingle.support.lost.dto.LostCaseAction;
import com.e106.tingle.support.lost.dto.LostCaseInput;
import com.e106.tingle.support.lost.dto.LostCasePage;
import com.e106.tingle.support.lost.dto.LostCaseReply;
import com.e106.tingle.support.lost.dto.LostCaseView;
import com.e106.tingle.support.lost.dto.LostReauthenticationInput;
import com.e106.tingle.support.lost.dto.LostReauthenticationView;
import com.e106.tingle.support.lost.dto.LostTargetConfirmation;
import com.e106.tingle.support.lost.dto.LostTargetPage;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.withdrawal.MemberWithdrawalService;
import com.e106.tingle.withdrawal.WithdrawalIdentityService;
import com.e106.tingle.withdrawal.WithdrawalReceiptTokens;
import com.e106.tingle.withdrawal.dto.WithdrawalPrepared;
import com.e106.tingle.withdrawal.dto.WithdrawalView;

/** Lost-device support never creates a login session or treats inquiry receipt as completed withdrawal. */
@Service
public class LostWithdrawalService {
	private static final Duration REAUTH_VALIDITY = Duration.ofMinutes(10);
	private static final Duration REPLY_VALIDITY = Duration.ofDays(14);
	private static final Duration CLOSED_RETENTION = Duration.ofHours(720);
	private final JdbcTemplate jdbc;
	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final SocialVerifier socialVerifier;
	private final WithdrawalIdentityService identities;
	private final MemberWithdrawalService withdrawals;
	private final WithdrawalReceiptTokens receipts;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final boolean schedulingEnabled;

	public LostWithdrawalService(JdbcTemplate jdbc, UserRepository users, RefreshTokenRepository sessions,
		SocialVerifier socialVerifier, WithdrawalIdentityService identities, MemberWithdrawalService withdrawals,
		WithdrawalReceiptTokens receipts, TransactionTemplate transactions, Clock clock,
		@Value("${app.lost-withdrawal.scheduling-enabled:${app.withdrawal.enabled:false}}") boolean schedulingEnabled) {
		this.jdbc = jdbc;
		this.users = users;
		this.sessions = sessions;
		this.socialVerifier = socialVerifier;
		this.identities = identities;
		this.withdrawals = withdrawals;
		this.receipts = receipts;
		this.transactions = transactions;
		this.clock = clock;
		this.schedulingEnabled = schedulingEnabled;
	}

	public LostTargetPage targets(long userId, UUID session) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			return new LostTargetPage(jdbc.query("select id,owner_generation,state::text,permanently_blocked_at "
				+ "from keyring where owner_user_id=? order by id", (row, index) -> new LostTargetPage.Target(
					row.getLong(1), row.getObject(2, UUID.class), row.getObject(2) != null
						&& "REGISTERED".equals(row.getString(3)) && row.getTimestamp(4) == null), userId));
		});
	}

	public LostReauthenticationView reauthenticate(long userId, UUID session, UUID requestKey,
		String providerPath, LostReauthenticationInput input) {
		SocialProvider provider = SocialProvider.fromPath(providerPath);
		var authentication = input.authentication();
		String requestHash = hash("reauth", provider.name(), input.deviceId(), authentication.token(),
			authentication.code(), authentication.codeVerifier(), authentication.redirectUri(), authentication.nonce());
		LostReauthenticationView replay = transactions.execute(status -> {
			User member = lockMember(userId, session);
			if (member.getProvider() != provider) {
				throw failure(ErrorCode.INVALID_SOCIAL_TOKEN);
			}
			return reauthenticationReplay(userId, session, requestKey, requestHash);
		});
		if (replay != null) {
			return replay;
		}
		// A provider code may be single use; successful request replay above never calls the provider again.
		SocialVerifier.SocialAccount account = socialVerifier.verify(provider, authentication);
		if (account == null || account.id() == null) {
			throw failure(ErrorCode.INVALID_SOCIAL_TOKEN);
		}
		return transactions.execute(status -> {
			User member = lockMember(userId, session);
			LostReauthenticationView duplicate = reauthenticationReplay(userId, session, requestKey, requestHash);
			if (duplicate != null) {
				return duplicate;
			}
			if (member.getProvider() != provider || !member.getProviderSubject().equals(account.id())) {
				throw failure(ErrorCode.INVALID_SOCIAL_TOKEN);
			}
			UUID generation = lockDevice(userId, input.deviceId(), null);
			Instant verified = clock.instant();
			UUID id = UUID.randomUUID();
			jdbc.update("insert into lost_reauthentication(id,user_id,keyring_id,owner_generation,session_id,"
				+ "request_key,request_hash,verified_at,expires_at) values(?,?,?,?,?,?,?,?,?)", id, userId,
				input.deviceId(), generation, session, requestKey, requestHash, time(verified),
				time(verified.plus(REAUTH_VALIDITY)));
			return new LostReauthenticationView(id, input.deviceId(), generation, verified,
				verified.plus(REAUTH_VALIDITY));
		});
	}

	public LostCaseView create(long userId, UUID session, UUID requestKey, LostCaseInput input) {
		return transactions.execute(status -> {
			// prepare() also acquires this identity lock. Take it before any member/case/device row.
			lockIdentity(userId);
			lockMemberRow(userId, session);
			String requestHash = hash("create", input.reauthenticationId(), input.reason());
			List<Binding> previous = bindings("where b.user_id=? and b.request_id=?", userId, requestKey);
			if (!previous.isEmpty()) {
				Binding binding = previous.getFirst();
				if (!requestHash.equals(binding.requestHash())) {
					throw failure(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				return view(binding);
			}
			if (!bindings("where b.user_id=? and c.closed_at is null", userId).isEmpty()) {
				throw failure(ErrorCode.CASE_STATE_CONFLICT);
			}
			Grant grant = grant(userId, session, input.reauthenticationId());
			lockDevice(userId, grant.device(), grant.generation());
			WithdrawalPrepared prepared = withdrawals.prepare(userId, session, requestKey, true);
			Instant now = clock.instant();
			Long caseId = jdbc.queryForObject("insert into service_case(requester_user_id,kind,reason,"
				+ "lost_keyring_id,status,created_at) values(?,'LOST_WITHDRAWAL',?,?,'RECEIVED',?) returning id",
				Long.class, userId, input.reason(), grant.device(), time(now));
			jdbc.update("insert into lost_withdrawal_request(case_id,user_id,request_id,request_hash,withdrawal_id,"
				+ "keyring_id,owner_generation,session_id,reauthentication_id,reauthenticated_at,expires_at) "
				+ "values(?,?,?,?,?,?,?,?,?,?,?)",
				caseId, userId, requestKey, requestHash, prepared.operation().withdrawalId(), grant.device(),
				grant.generation(), session, grant.id(), time(grant.verified()), time(grant.expires()));
			history(caseId, "STATUS_CHANGED", userId, "RECEIVED");
			return view(binding(caseId, false));
		});
	}

	public LostCasePage memberCases(long userId, UUID session, long after) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			return page("where b.user_id=? and c.id>?", userId, after);
		});
	}

	public LostCaseView memberCase(long userId, UUID session, long caseId) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			return view(owned(userId, caseId));
		});
	}

	public LostCaseView refreshReauthentication(long userId, UUID session, long caseId, UUID grantId) {
		return transactions.execute(status -> {
			// Never enter prepare() while holding member/case/device rows without the identity lock.
			lockIdentity(userId);
			lockMemberRow(userId, session);
			Binding binding = owned(userId, caseId);
			requireOpen(binding);
			Grant grant = grant(userId, session, grantId);
			if (grant.device() != binding.device() || !grant.generation().equals(binding.generation())) {
				throw failure(ErrorCode.CASE_STATE_CONFLICT);
			}
			lockDevice(userId, grant.device(), grant.generation());
			if (session.equals(binding.session()) && grant.id().equals(binding.reauthenticationId())) {
				return view(binding);
			}
			WithdrawalPrepared prepared = withdrawals.prepare(userId, session, binding.requestId(), true);
			jdbc.update("update lost_withdrawal_request set session_id=?,reauthentication_id=?,"
				+ "reauthenticated_at=?,expires_at=?,"
				+ "reviewed_at=null,confirmed_at=null,withdrawal_id=? where case_id=?", session,
				grant.id(), time(grant.verified()), time(grant.expires()), prepared.operation().withdrawalId(), caseId);
			return view(binding(caseId, false));
		});
	}

	public LostCaseView confirm(long userId, UUID session, long caseId, UUID requestKey,
		LostTargetConfirmation input) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			Binding binding = owned(userId, caseId);
			String command = hash("confirm", input.deviceId(), input.ownerGeneration(), input.confirmed());
			if (replayed(caseId, requestKey, command)) {
				return view(binding);
			}
			requireOpen(binding);
			checkTarget(binding, input.deviceId(), input.ownerGeneration());
			if (Boolean.TRUE.equals(input.confirmed())) {
				requireFresh(binding, session);
				if (!"REVIEWING".equals(binding.status()) || binding.reviewed() == null) {
					throw failure(ErrorCode.CASE_STATE_CONFLICT);
				}
				lockDevice(userId, binding.device(), binding.generation());
			}
			jdbc.update("update lost_withdrawal_request set confirmed_at=? where case_id=?",
				Boolean.TRUE.equals(input.confirmed()) ? time(clock.instant()) : null, caseId);
			recordCommand(caseId, requestKey, command);
			return view(binding(caseId, false));
		});
	}

	public LostCaseView acknowledgeInfo(long userId, UUID session, long caseId, long infoRequestId) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			Binding binding = owned(userId, caseId);
			if (!"NEEDS_INFO".equals(binding.status()) || !Objects.equals(binding.infoRequestId(), infoRequestId)) {
				throw failure(ErrorCode.CASE_STATE_CONFLICT);
			}
			if (binding.delivered() == null) {
				Instant delivered = clock.instant();
				jdbc.update("update lost_withdrawal_request set info_delivered_at=? where case_id=?",
					time(delivered), caseId);
				jdbc.update("update service_case set reply_due_at=? where id=?",
					time(delivered.plus(REPLY_VALIDITY)), caseId);
			}
			return view(binding(caseId, false));
		});
	}

	public LostCaseView reply(long userId, UUID session, long caseId, UUID requestKey, LostCaseReply input) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			Binding binding = owned(userId, caseId);
			String command = hash("reply", input.infoRequestId(), input.message());
			if (replayed(caseId, requestKey, command)) {
				return view(binding);
			}
			if (!"NEEDS_INFO".equals(binding.status())
				|| !Objects.equals(binding.infoRequestId(), input.infoRequestId())
				|| (binding.replyDue() != null && !clock.instant().isBefore(binding.replyDue()))) {
				throw failure(ErrorCode.CASE_STATE_CONFLICT);
			}
			history(caseId, "USER_REPLIED", userId, input.message());
			jdbc.update("update service_case set status='REVIEWING',reply_due_at=null where id=?", caseId);
			jdbc.update("update lost_withdrawal_request set info_request_id=null,info_delivered_at=null,"
				+ "reviewed_at=null,confirmed_at=null where case_id=?", caseId);
			recordCommand(caseId, requestKey, command);
			return view(binding(caseId, false));
		});
	}

	public LostCaseView cancel(long userId, UUID session, long caseId) {
		return transactions.execute(status -> {
			lockMember(userId, session);
			Binding binding = owned(userId, caseId);
			if (binding.closed() == null) {
				close(binding, "REJECTED", clock.instant());
				history(caseId, "STATUS_CHANGED", userId, "USER_CANCELLED");
			}
			return view(binding(caseId, false));
		});
	}

	public LostCasePage operatorCases(long actor, UUID session, long after) {
		return transactions.execute(status -> {
			lockOperator(actor, session);
			audit(actor, null, null, "CASE_HANDLE", "SUCCESS", "LIST");
			return page("where c.id>?", after);
		});
	}

	public LostCaseView operatorCase(long actor, UUID session, long caseId) {
		return transactions.execute(status -> {
			Binding binding = lockForOperator(actor, session, caseId);
			audit(actor, binding, binding.userId(), "CASE_HANDLE", "SUCCESS", "VIEW");
			return view(binding);
		});
	}

	public LostCaseView operatorAction(long actor, UUID session, long caseId, UUID requestKey,
		LostCaseAction input) {
		return transactions.execute(status -> {
			Binding binding = lockForOperator(actor, session, caseId);
			String command = hash("operator", input.action(), input.deviceId(), input.ownerGeneration(),
				input.message());
			if (replayed(caseId, requestKey, command)) {
				return view(binding);
			}
			requireOpen(binding);
			switch (input.action()) {
				case "REVIEW" -> {
					if (!List.of("RECEIVED", "REVIEWING").contains(binding.status())) {
						throw failure(ErrorCode.CASE_STATE_CONFLICT);
					}
					jdbc.update("update service_case set status='REVIEWING',assigned_operator_id=?,"
						+ "reply_due_at=null where id=?", actor, caseId);
				}
				case "VERIFY" -> {
					if (!"REVIEWING".equals(binding.status()) || input.deviceId() == null) {
						throw failure(ErrorCode.CASE_STATE_CONFLICT);
					}
					checkTarget(binding, input.deviceId(), input.ownerGeneration());
					requireFresh(binding, null);
					lockDevice(binding.userId(), binding.device(), binding.generation());
					jdbc.update("update lost_withdrawal_request set reviewed_at=?,confirmed_at=null where case_id=?",
						time(clock.instant()), caseId);
					jdbc.update("update service_case set assigned_operator_id=? where id=?", actor, caseId);
				}
				case "REQUEST_INFO" -> {
					requireMessage(input.message());
					long historyId = history(caseId, "INFO_REQUESTED", actor, input.message());
					jdbc.update("update service_case set status='NEEDS_INFO',assigned_operator_id=?,"
						+ "reply_due_at=null where id=?", actor, caseId);
					jdbc.update("update lost_withdrawal_request set info_request_id=?,info_delivered_at=null,"
						+ "reviewed_at=null,confirmed_at=null where case_id=?", historyId, caseId);
				}
				case "REJECT" -> {
					requireMessage(input.message());
					close(binding, "REJECTED", clock.instant());
					history(caseId, "STATUS_CHANGED", actor, input.message());
				}
				default -> throw failure(ErrorCode.INVALID_REQUEST);
			}
			recordCommand(caseId, requestKey, command);
			audit(actor, binding, binding.userId(), "CASE_HANDLE", "SUCCESS", input.action());
			return view(binding(caseId, false));
		});
	}

	public WithdrawalView complete(long actor, UUID session, UUID withdrawalId, LostTargetConfirmation input) {
		Binding preliminary = transactions.execute(status -> {
			lockOperator(actor, session);
			List<Binding> found = bindings("where b.withdrawal_id=?", withdrawalId);
			if (found.isEmpty()) {
				boolean completed = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 "
					+ "from withdrawal_operation where id=? and kind='LOST' and completed_at is not null)",
					Boolean.class, withdrawalId));
				if (!completed) {
					throw failure(ErrorCode.RESOURCE_NOT_FOUND);
				}
				return null;
			}
			return found.getFirst();
		});
		if (preliminary == null) {
			return withdrawals.executeForOperator(0, withdrawalId, actor, session, () -> { });
		}
		try {
			return withdrawals.executeForOperator(preliminary.userId(), withdrawalId, actor, session, () -> {
				Binding binding = binding(preliminary.caseId(), true);
				if (actor == binding.userId()) {
					throw failure(ErrorCode.FORBIDDEN);
				}
				checkTarget(binding, input.deviceId(), input.ownerGeneration());
				if (!Boolean.TRUE.equals(input.confirmed()) || !"REVIEWING".equals(binding.status())
					|| binding.reviewed() == null || binding.confirmed() == null) {
					throw failure(ErrorCode.CASE_STATE_CONFLICT);
				}
				requireFresh(binding, null);
				lockDevice(binding.userId(), binding.device(), binding.generation());
				if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from keyring "
					+ "where owner_user_id=? and id<>?)", Boolean.class, binding.userId(), binding.device()))) {
					throw failure(ErrorCode.WITHDRAWAL_KEYRING_REGISTERED);
				}
				if (tableExists("keyring_reset_run") && Boolean.TRUE.equals(jdbc.queryForObject(
					"select exists(select 1 from keyring_reset_run where owner_user_id=? and keyring_id<>? "
						+ "and completed_at is null)", Boolean.class, binding.userId(), binding.device()))) {
					throw failure(ErrorCode.WITHDRAWAL_RESET_PENDING);
				}
				Instant now = clock.instant();
				jdbc.update("update keyring set permanently_blocked_at=?,credential_revoked_at=?,owner_user_id=null,"
					+ "owner_generation=null,state='UNBOUND',synced_settings_version=null,synced_at=null where id=?",
					time(now), time(now), binding.device());
				if (tableExists("keyring_reset_run")) {
					// Invalidate the lost device's work; never invent a verified completion proof.
					jdbc.update("delete from keyring_reset_run where keyring_id=? and owner_user_id=?",
						binding.device(), binding.userId());
				}
				jdbc.update("update service_case set status='COMPLETED',closed_at=?,reply_due_at=null where id=?",
					time(now), binding.caseId());
				history(binding.caseId(), "ACTION_DONE", actor, "LOST_BLOCK_AND_WITHDRAW");
				audit(actor, binding, binding.userId(), "KEYRING_UNBIND", "SUCCESS", "PERMANENT_BLOCK");
				audit(actor, binding, binding.userId(), "MEMBER_WITHDRAW", "SUCCESS", "LOST_WITHDRAWAL");
				jdbc.update("delete from lost_case_command where case_id=?", binding.caseId());
				jdbc.update("delete from lost_withdrawal_request where case_id=?", binding.caseId());
				jdbc.update("delete from lost_reauthentication where user_id=?", binding.userId());
			});
		} catch (BusinessException exception) {
			recordFailure(actor, session, preliminary.caseId(), exception.getErrorCode().name());
			throw exception;
		} catch (RuntimeException exception) {
			recordFailure(actor, session, preliminary.caseId(), ErrorCode.INTERNAL_ERROR.name());
			throw exception;
		}
	}

	@Scheduled(fixedDelayString = "${app.lost-withdrawal.retention-poll-ms:60000}")
	public void poll() {
		if (schedulingEnabled) {
			purge();
		}
	}

	public void purge() {
		List<Long> due = jdbc.queryForList("select b.case_id from lost_withdrawal_request b "
			+ "join service_case c on c.id=b.case_id where c.status='NEEDS_INFO' and c.reply_due_at<=? "
			+ "order by b.case_id limit 100", Long.class, time(clock.instant()));
		for (long caseId : due) {
			transactions.executeWithoutResult(status -> {
				List<Binding> candidates = bindings("where b.case_id=?", caseId);
				if (candidates.isEmpty()) {
					return;
				}
				Binding candidate = candidates.getFirst();
				User member = users.findById(candidate.userId()).orElse(null);
				if (member == null) {
					return;
				}
				jdbc.queryForList("select id from users where id=? for update", Long.class, candidate.userId());
				List<Binding> current = bindings("where b.case_id=? for update of b,c", caseId);
				if (current.isEmpty()) {
					return;
				}
				Binding binding = current.getFirst();
				if ("NEEDS_INFO".equals(binding.status()) && binding.replyDue() != null
					&& !clock.instant().isBefore(binding.replyDue())) {
					close(binding, "NO_RESPONSE", binding.replyDue());
					history(caseId, "STATUS_CHANGED", null, "NO_RESPONSE");
				}
			});
		}
		Timestamp cutoff = time(clock.instant().minus(CLOSED_RETENTION));
		List<Long> expired = jdbc.queryForList("select b.case_id from lost_withdrawal_request b "
			+ "join service_case c on c.id=b.case_id where c.closed_at<=? order by b.case_id limit 100",
			Long.class, cutoff);
		for (long caseId : expired) {
			transactions.executeWithoutResult(status -> {
				List<Binding> candidates = bindings("where b.case_id=?", caseId);
				if (candidates.isEmpty()) {
					return;
				}
				Binding candidate = candidates.getFirst();
				User member = users.findById(candidate.userId()).orElse(null);
				if (member == null) {
					return;
				}
				jdbc.queryForList("select id from users where id=? for update", Long.class, candidate.userId());
				List<Binding> current = bindings("where b.case_id=? for update of b,c", caseId);
				if (current.isEmpty() || current.getFirst().closed() == null
					|| current.getFirst().closed().isAfter(cutoff.toInstant())) {
					return;
				}
				jdbc.update("update operator_audit set target_service_case_id=null where target_service_case_id=?",
					caseId);
				jdbc.update("delete from lost_case_command where case_id=?", caseId);
				jdbc.update("delete from lost_withdrawal_request where case_id=?", caseId);
				jdbc.update("delete from service_case_history where service_case_id=?", caseId);
				jdbc.update("delete from service_case where id=?", caseId);
			});
		}
		transactions.executeWithoutResult(status -> jdbc.update("delete from lost_reauthentication "
			+ "where expires_at<=?", time(clock.instant())));
	}

	private LostReauthenticationView reauthenticationReplay(long userId, UUID session, UUID key, String hash) {
		List<Grant> grants = jdbc.query("select * from lost_reauthentication where user_id=? and request_key=?",
			(row, index) -> readGrant(row), userId, key);
		if (grants.isEmpty()) {
			return null;
		}
		Grant grant = grants.getFirst();
		if (!hash.equals(grant.requestHash()) || !session.equals(grant.session())) {
			throw failure(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return new LostReauthenticationView(grant.id(), grant.device(), grant.generation(),
			grant.verified(), grant.expires());
	}

	private Grant grant(long userId, UUID session, UUID id) {
		Grant grant = jdbc.query("select * from lost_reauthentication where id=? and user_id=?",
			(row, index) -> readGrant(row), id, userId).stream().findFirst()
			.orElseThrow(() -> failure(ErrorCode.REAUTHENTICATION_REQUIRED));
		if (!session.equals(grant.session()) || !clock.instant().isBefore(grant.expires())) {
			throw failure(ErrorCode.REAUTHENTICATION_REQUIRED);
		}
		return grant;
	}

	private User lockMember(long userId, UUID session) {
		User member = lockIdentity(userId);
		lockMemberRow(userId, session);
		return member;
	}

	private User lockIdentity(long userId) {
		User member = users.findById(userId).orElseThrow(() -> failure(ErrorCode.AUTH_REQUIRED));
		identities.lockIdentity(member);
		return member;
	}

	private void lockMemberRow(long userId, UUID session) {
		jdbc.queryForList("select id from users where id=? for update", Long.class, userId);
		requireSession(userId, session);
	}

	private void requireSession(long userId, UUID session) {
		if (session == null || !sessions.isPushSessionUsable(session, userId, clock.instant())
			|| !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users "
				+ "where id=? and status='ACTIVE')", Boolean.class, userId))) {
			throw failure(ErrorCode.SESSION_REVOKED);
		}
	}

	private void requireOperator(long actor, UUID session) {
		requireSession(actor, session);
		if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users "
			+ "where id=? and is_operator)", Boolean.class, actor))) {
			throw failure(ErrorCode.FORBIDDEN);
		}
	}

	private void lockOperator(long actor, UUID session) {
		jdbc.queryForList("select id from users where id=? for update", Long.class, actor);
		requireOperator(actor, session);
	}

	private Binding lockForOperator(long actor, UUID session, long caseId) {
		requireOperator(actor, session);
		Binding preliminary = binding(caseId, false);
		if (actor == preliminary.userId()) {
			throw failure(ErrorCode.FORBIDDEN);
		}
		User member = users.findById(preliminary.userId()).orElseThrow(() -> failure(ErrorCode.RESOURCE_NOT_FOUND));
		identities.lockIdentity(member);
		for (long id : List.of(actor, preliminary.userId()).stream().sorted().toList()) {
			jdbc.queryForList("select id from users where id=? for update", Long.class, id);
		}
		requireOperator(actor, session);
		return binding(caseId, true);
	}

	private UUID lockDevice(long userId, long device, UUID expectedGeneration) {
		List<UUID> found = jdbc.query("select owner_generation from keyring where id=? and owner_user_id=? "
			+ "and state='REGISTERED' and permanently_blocked_at is null for update",
			(row, index) -> row.getObject(1, UUID.class), device, userId);
		if (found.isEmpty() || found.getFirst() == null
			|| (expectedGeneration != null && !expectedGeneration.equals(found.getFirst()))) {
			throw failure(ErrorCode.CASE_STATE_CONFLICT);
		}
		return found.getFirst();
	}

	private void requireFresh(Binding binding, UUID expectedSession) {
		if (!clock.instant().isBefore(binding.expires())
			|| (expectedSession != null && !expectedSession.equals(binding.session()))
			|| !sessions.isPushSessionUsable(binding.session(), binding.userId(), clock.instant())) {
			throw failure(ErrorCode.REAUTHENTICATION_REQUIRED);
		}
	}

	private static void checkTarget(Binding binding, long device, UUID generation) {
		if (binding.device() != device || !binding.generation().equals(generation)) {
			throw failure(ErrorCode.CASE_STATE_CONFLICT);
		}
	}

	private void requireOpen(Binding binding) {
		if (binding.closed() != null || ("NEEDS_INFO".equals(binding.status()) && binding.replyDue() != null
			&& !clock.instant().isBefore(binding.replyDue()))) {
			throw failure(ErrorCode.CASE_STATE_CONFLICT);
		}
	}

	private Binding owned(long userId, long caseId) {
		return bindings("where b.case_id=? and b.user_id=? for update of b,c", caseId, userId).stream().findFirst()
			.orElseThrow(() -> failure(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private Binding binding(long caseId, boolean lock) {
		return bindings("where b.case_id=?" + (lock ? " for update of b,c" : ""), caseId).stream().findFirst()
			.orElseThrow(() -> failure(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private List<Binding> bindings(String suffix, Object... args) {
		return jdbc.query("select b.*,c.status::text,c.reply_due_at,c.closed_at from lost_withdrawal_request b "
			+ "join service_case c on c.id=b.case_id " + suffix, (row, index) -> new Binding(row.getLong("case_id"),
				row.getLong("user_id"), row.getObject("request_id", UUID.class), row.getString("request_hash"),
				row.getObject("withdrawal_id", UUID.class), row.getLong("keyring_id"),
				row.getObject("owner_generation", UUID.class), row.getObject("session_id", UUID.class),
				row.getObject("reauthentication_id", UUID.class),
				instant(row, "expires_at"), instant(row, "reviewed_at"), instant(row, "confirmed_at"),
				row.getObject("info_request_id", Long.class), instant(row, "info_delivered_at"),
				row.getString("status"),
				instant(row, "reply_due_at"), instant(row, "closed_at")), args);
	}

	private LostCaseView view(Binding binding) {
		if (binding.closed() != null && !clock.instant().isBefore(binding.closed().plus(CLOSED_RETENTION))) {
			// Logical expiry applies before loading bodies even if a deletion worker is delayed.
			throw failure(ErrorCode.RESOURCE_NOT_FOUND);
		}
		List<LostCaseView.History> history = new ArrayList<>(jdbc.query("select id,event::text,message,created_at "
			+ "from service_case_history where service_case_id=? order by id desc limit 100",
			(row, index) -> new LostCaseView.History(row.getLong(1), row.getString(2), row.getString(3),
				row.getTimestamp(4).toInstant()), binding.caseId()));
		Collections.reverse(history);
		boolean reauthentication = binding.closed() == null && (!clock.instant().isBefore(binding.expires())
			|| !sessions.isPushSessionUsable(binding.session(), binding.userId(), clock.instant()));
		boolean receiptExists = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 "
			+ "from withdrawal_operation where id=? and expires_at>?)", Boolean.class, binding.withdrawal(),
			time(clock.instant())));
		String receipt = receiptExists ? receipts.issue(binding.withdrawal()) : null;
		WithdrawalView withdrawal = receiptExists ? withdrawals.result(binding.withdrawal(), receipt) : null;
		return new LostCaseView(binding.caseId(), binding.status(), binding.device(), binding.generation(),
			reauthentication, binding.reviewed() != null, binding.confirmed() != null, binding.infoRequestId(),
			binding.replyDue(), binding.closed(), Messages.get("lost.case." + binding.status()), List.copyOf(history),
			receipt, withdrawal);
	}

	private LostCasePage page(String suffix, Object... args) {
		Object[] parameters = java.util.Arrays.copyOf(args, args.length + 1);
		parameters[args.length] = time(clock.instant().minus(CLOSED_RETENTION));
		List<LostCasePage.Item> items = jdbc.query("select c.id,c.status::text,b.keyring_id,"
			+ "c.created_at,c.reply_due_at "
			+ "from service_case c join lost_withdrawal_request b on b.case_id=c.id " + suffix
			+ " and (c.closed_at is null or c.closed_at>?) order by c.id limit 51",
			(row, index) -> new LostCasePage.Item(row.getLong(1), row.getString(2), row.getLong(3),
				row.getTimestamp(4).toInstant(), instant(row, "reply_due_at")), parameters);
		boolean more = items.size() > 50;
		List<LostCasePage.Item> visible = more ? items.subList(0, 50) : items;
		return new LostCasePage(List.copyOf(visible), more ? visible.getLast().caseId() : null);
	}

	private boolean replayed(long caseId, UUID key, String hash) {
		List<String> stored = jdbc.queryForList("select request_hash from lost_case_command "
			+ "where case_id=? and request_key=?", String.class, caseId, key);
		if (!stored.isEmpty() && !hash.equals(stored.getFirst())) {
			throw failure(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return !stored.isEmpty();
	}

	private void recordCommand(long caseId, UUID key, String hash) {
		jdbc.update("insert into lost_case_command(id,case_id,request_key,request_hash,created_at) values(?,?,?,?,?)",
			UUID.randomUUID(), caseId, key, hash, time(clock.instant()));
	}

	private long history(long caseId, String event, Long actor, String message) {
		return jdbc.queryForObject("insert into service_case_history(service_case_id,event,actor_user_id,message,"
			+ "created_at) values(?,cast(? as service_case_event),?,?,?) returning id", Long.class, caseId,
			event, actor, message, time(clock.instant()));
	}

	private void close(Binding binding, String status, Instant closedAt) {
		withdrawals.discardPreparedUnderMemberLock(binding.userId(), binding.withdrawal());
		jdbc.update("update service_case set status=cast(? as service_case_status),closed_at=?,reply_due_at=null "
			+ "where id=? and closed_at is null", status, time(closedAt), binding.caseId());
		jdbc.update("update lost_withdrawal_request set reviewed_at=null,confirmed_at=null where case_id=?",
			binding.caseId());
	}

	private void audit(long actor, Binding binding, Long target, String action, String result, String reason) {
		jdbc.update("insert into operator_audit(operator_id,kind,action,target_user_id,target_service_case_id,"
			+ "target_keyring_id,result_code,reason,occurred_at) values(?,'ACCESS',cast(? as operator_action),?,?,?,"
			+ "cast(? as operator_result),?,?)", actor, action, target, binding == null ? null : binding.caseId(),
			binding == null ? null : binding.device(), result, reason, time(clock.instant()));
	}

	private void recordFailure(long actor, UUID session, long caseId, String code) {
		try {
			transactions.executeWithoutResult(status -> {
				Binding binding = lockForOperator(actor, session, caseId);
				history(caseId, "ACTION_FAILED", actor, code);
				audit(actor, binding, binding.userId(), "CASE_HANDLE", "FAILED", code);
			});
		} catch (RuntimeException ignored) {
			// Revoked permissions or a concurrently completed case must not change the original failure.
		}
	}

	private boolean tableExists(String name) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, name));
	}

	private static void requireMessage(String message) {
		if (message == null || message.isBlank() || message.length() > 2000) {
			throw failure(ErrorCode.INVALID_REQUEST);
		}
	}

	private static Grant readGrant(ResultSet row) throws SQLException {
		return new Grant(row.getObject("id", UUID.class), row.getLong("keyring_id"),
			row.getObject("owner_generation", UUID.class), row.getObject("session_id", UUID.class),
			row.getString("request_hash"), instant(row, "verified_at"), instant(row, "expires_at"));
	}

	private static Instant instant(ResultSet row, String name) throws SQLException {
		Timestamp value = row.getTimestamp(name);
		return value == null ? null : value.toInstant();
	}

	private static Timestamp time(Instant instant) {
		return Timestamp.from(instant);
	}

	private static String hash(Object... values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Object value : values) {
				byte[] encoded = (value == null ? "" : value.toString()).getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : encoded.length).array());
				digest.update(encoded);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static BusinessException failure(ErrorCode code) {
		return new BusinessException(code);
	}

	private record Grant(UUID id, long device, UUID generation, UUID session, String requestHash,
		Instant verified, Instant expires) {
	}

	private record Binding(long caseId, long userId, UUID requestId, String requestHash, UUID withdrawal,
		long device, UUID generation, UUID session, UUID reauthenticationId, Instant expires,
		Instant reviewed, Instant confirmed,
		Long infoRequestId, Instant delivered, String status, Instant replyDue, Instant closed) {
	}
}

package com.e106.tingle.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;

import lombok.RequiredArgsConstructor;

/** Account removal joins the caller's transaction; photo cleanup must already be durably queued. */
@Service
@RequiredArgsConstructor
public class MemberWithdrawalCleanup {
	private final JdbcTemplate jdbc;
	private final SettingsChangeCoordinator changes;
	private final MemberWithdrawalDeletionInvalidator deletionApprovals;

	/** Include retained chats and block-only peers; the caller locks these users in ascending order. */
	public List<Long> relatedMemberIds(long userId) {
		List<Long> members = jdbc.queryForList("select distinct u.id from users u where u.id=? or u.id in "
			+ "(select user_low_id from matching where user_low_id=? or user_high_id=? "
			+ "union select user_high_id from matching where user_low_id=? or user_high_id=? "
			+ "union select p.user_id from chat_participant p where p.chat_id in "
			+ "(select chat_id from chat_participant where user_id=?)) order by u.id", Long.class,
			userId, userId, userId, userId, userId, userId);
		if (!exists("user_block")) {
			return members;
		}
		List<Long> blocked = jdbc.queryForList("select u.id from users u where u.id in "
			+ "(select owner_id from user_block where owner_id=? or target_id=? "
			+ "union select target_id from user_block where owner_id=? or target_id=?)", Long.class,
			userId, userId, userId, userId);
		return java.util.stream.Stream.concat(members.stream(), blocked.stream()).distinct().sorted().toList();
	}

	/** Caller has archived permitted relation evidence, queued photos and locked all related user rows. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void cleanupUnderLocks(long userId, Instant completedAt) {
		List<Long> others = relatedMemberIds(userId).stream().filter(id -> id != userId).toList();
		List<Long> matchings = jdbc.queryForList("select m.id from matching m where m.user_low_id=? "
			+ "or m.user_high_id=? or exists (select 1 from chat c join chat_participant p on p.chat_id=c.id "
			+ "where c.matching_id=m.id and p.user_id=?) order by m.id for update", Long.class,
			userId, userId, userId);
		for (long matchingId : matchings) {
			closeAndDetach(matchingId, userId, completedAt);
		}
		if (exists("user_block")) {
			jdbc.update("delete from user_block where owner_id=? or target_id=?", userId, userId);
		}
		for (long other : others) {
			advanceCounterpart(other);
		}
		deleteMemberData(userId, completedAt);
	}

	private void advanceCounterpart(long userId) {
		boolean active = Boolean.TRUE.equals(jdbc.queryForObject(
			"select status='ACTIVE' from users where id=?", Boolean.class, userId));
		if (active) {
			changes.advanceAndCapture(userId);
			return;
		}
		List<Long> versions = jdbc.queryForList("select settings_version from user_setting where user_id=? "
			+ "for update", Long.class, userId);
		if (versions.isEmpty()) {
			return;
		}
		Long version = versions.getFirst();
		if (version == null || version < 0 || version == Long.MAX_VALUE) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		// The workflow never loads this inactive peer's setting into JPA. Advance the invalidation generation
		// without manufacturing an ACTIVE input snapshot or making its status block another member's removal.
		jdbc.update("update user_setting set settings_version=settings_version+1 where user_id=?", userId);
	}

	private void closeAndDetach(long matchingId, long userId, Instant now) {
		List<Long> rooms = jdbc.queryForList("select id from chat where matching_id=? order by id for update",
			Long.class, matchingId);
		for (long room : rooms) {
			jdbc.queryForList("select id from chat_participant where chat_id=? order by id for update", Long.class,
				room);
			jdbc.update("update chat set closed_at=least(expires_at,?),close_reason=case when expires_at<=? "
				+ "then 'EXPIRED'::chat_close_reason else 'LEFT'::chat_close_reason end,"
				+ "delete_at=greatest(delete_at,retain_until,least(expires_at,?)+interval '720 hours') "
				+ "where id=? and closed_at is null", Timestamp.from(now), Timestamp.from(now),
				Timestamp.from(now), room);
			jdbc.update("update chat set delete_at=greatest(delete_at,retain_until,closed_at+interval '720 hours') "
				+ "where id=?", room);
			jdbc.update("update chat_participant set left_at=coalesce(left_at,"
				+ "(select closed_at from chat where id=?)),notice_state='{}'::jsonb where chat_id=?", room, room);
			jdbc.update("update chat_participant set user_id=null,read_state='{}'::jsonb "
				+ "where chat_id=? and user_id=?", room, userId);
			jdbc.update("update chat_message set push_state='{}'::jsonb where chat_id=?", room);
			jdbc.update("update chat set ai_state='{}'::jsonb,icebreakers='{}'::jsonb where id=?", room);
		}
		deletionApprovals.invalidateChangedMetadata(matchingId, now);
		jdbc.update("delete from matching_participant where matching_id=?", matchingId);
		boolean workerOwner = !rooms.isEmpty() || Boolean.TRUE.equals(jdbc.queryForObject(
			"select chat_deletion_state is not null or chat_external_cleanup_state is not null "
				+ "from matching where id=?", Boolean.class, matchingId));
		if (workerOwner) {
			jdbc.update("update matching set user_low_id=null,user_high_id=null,status='CLOSED',evaluation=null,"
				+ "legacy_state=null,met_at=null,policy_version=null,opportunity_expires_at=null,"
				+ "counterpart_deadline_at=null,proposed_at=null,raw_delete_at=null where id=?", matchingId);
		} else {
			jdbc.update("delete from matching where id=?", matchingId);
		}
	}

	private void deleteMemberData(long userId, Instant now) {
		if (exists("lost_withdrawal_request")) {
			jdbc.update("delete from lost_case_command where case_id in "
				+ "(select case_id from lost_withdrawal_request where user_id=?)", userId);
			jdbc.update("delete from lost_withdrawal_request where user_id=?", userId);
			jdbc.update("delete from lost_reauthentication where user_id=?", userId);
		}
		jdbc.update("update operator_audit set target_service_case_id=null,reason=null where target_service_case_id in "
			+ "(select id from service_case where requester_user_id=?)", userId);
		jdbc.update("delete from service_case_history where service_case_id in "
			+ "(select id from service_case where requester_user_id=?)", userId);
		jdbc.update("delete from service_case where requester_user_id=?", userId);
		jdbc.update("update service_case set assigned_operator_id=null where assigned_operator_id=?", userId);
		jdbc.update("update service_case_history set actor_user_id=null where actor_user_id=?", userId);
		jdbc.update("update operator_audit set target_user_id=null,reason=null where target_user_id=?", userId);
		jdbc.update("update operator_audit set operator_id=null where operator_id=?", userId);
		jdbc.update("update rule_audit set actor_id=null where actor_id=?", userId);
		// Rule artifact/release creator and approval provenance are separate immutable security records.
		// Withdrawal does not erase those IDs or silently disable the no-self-approval evidence.
		jdbc.update("delete from registration_challenge where user_id=?", userId);
		jdbc.update("delete from keyring_sync_run where owner_user_id=?", userId);
		if (exists("keyring_reset_run")) {
			jdbc.update("delete from keyring_reset_run where owner_user_id=?", userId);
		}
		jdbc.update("delete from personality where survey_id in (select id from survey where user_id=?)", userId);
		jdbc.update("delete from survey_answer where survey_id in (select id from survey where user_id=?)", userId);
		for (String table : List.of("survey", "profile", "matching_preference", "settings_snapshot",
			"user_setting", "user_consents", "refresh_token")) {
			jdbc.update("delete from " + table + " where user_id=?", userId);
		}
		jdbc.update("update push_installation set user_id=null,login_family_id=null,binding_id=null,linked_at=null,"
			+ "updated_at=? where user_id=?", Timestamp.from(now), userId);
		jdbc.update("delete from users where id=?", userId);
	}

	private boolean exists(String table) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table));
	}
}

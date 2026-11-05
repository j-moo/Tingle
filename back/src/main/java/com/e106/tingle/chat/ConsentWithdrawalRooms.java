package com.e106.tingle.chat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 동의 변경과 같은 트랜잭션에서 철회 당시 참여 중인 방만 종료한다. */
@Service
@RequiredArgsConstructor
public class ConsentWithdrawalRooms {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	@Transactional(propagation = Propagation.MANDATORY)
	public Instant close(long userId) {
		// The caller owns its user lock. Lock pending matchings too, then re-read rooms after concurrent acceptance.
		List<Long> matchings = jdbc.queryForList("select id from matching where user_low_id=? or user_high_id=? "
			+ "order by id for update", Long.class, userId, userId);
		Instant withdrawnAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		for (long matchingId : matchings) {
			List<Long> rooms = jdbc.queryForList("select c.id from chat c join chat_participant p on p.chat_id=c.id "
				+ "where c.matching_id=? and p.user_id=? and p.left_at is null and c.closed_at is null "
				+ "and c.expires_at>? order by c.id for update of c", Long.class, matchingId, userId,
				Timestamp.from(withdrawnAt));
			for (long roomId : rooms) {
				closeRoom(roomId, matchingId, userId, withdrawnAt);
			}
		}
		return withdrawnAt;
	}

	private void closeRoom(long roomId, long matchingId, long userId, Instant withdrawnAt) {
		Timestamp closed = Timestamp.from(withdrawnAt);
		Timestamp deletion = Timestamp.from(withdrawnAt.plus(Duration.ofDays(30)));
		jdbc.update("update chat_participant set left_at=coalesce(left_at,?) where chat_id=? and user_id=?",
			closed, roomId, userId);
		jdbc.update("update chat set closed_at=?,close_reason='LEFT', "
			+ "delete_at=greatest(coalesce(delete_at,?),?,coalesce(retain_until,?)) "
			+ "where id=? and closed_at is null", closed, deletion, deletion, deletion, roomId);
		jdbc.update("update matching set status='CLOSED' where id=?", matchingId);
	}
}

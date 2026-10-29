package com.e106.tingle.chat.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ChatDeletionRepository {
	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;

	public Optional<Long> lockNext(Instant now) {
		List<Long> rows = jdbc.queryForList("select m.id from matching m join chat c on c.matching_id=m.id "
			+ "where coalesce(c.closed_at,c.expires_at)+interval '720 hours'<=? "
			+ "and coalesce(m.chat_deletion_state->>'status','NEW') not in "
			+ "('BLOCKED_SOURCE_CHANGED','BLOCKED_GENERATION') "
			+ "and (m.chat_deletion_state->>'nextAttemptAt' is null "
			+ "or cast(m.chat_deletion_state->>'nextAttemptAt' as timestamptz)<=?) "
			+ "and (coalesce(m.chat_deletion_state->>'status','NEW')<>'RUNNING' "
			+ "or cast(m.chat_deletion_state->>'leaseUntil' as timestamptz)<=?) "
			+ "order by coalesce(c.closed_at,c.expires_at),c.id limit 1 for update of m skip locked",
			Long.class, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
		return rows.stream().findFirst();
	}

	public boolean lockMatching(long matchingId) {
		return !jdbc.queryForList("select id from matching where id=? for update", Long.class, matchingId).isEmpty();
	}

	public Optional<Context> lockContext(long matchingId, Instant now, Instant minimumDue) {
		List<Context> contexts = jdbc.query("select id,created_at,expires_at,closed_at,delete_at,retain_until "
			+ "from chat where matching_id=? for update", (rs, row) -> {
				Instant closed = rs.getTimestamp("closed_at") == null ? rs.getTimestamp("expires_at").toInstant()
					: rs.getTimestamp("closed_at").toInstant();
				Instant due = closed.plus(Duration.ofHours(720));
				for (String column : List.of("retain_until", "delete_at")) {
					Timestamp retained = rs.getTimestamp(column);
					if (retained != null && retained.toInstant().isAfter(due)) {
						due = retained.toInstant();
					}
				}
				if (minimumDue != null && minimumDue.isAfter(due)) {
					due = minimumDue;
				}
				return new Context(matchingId, rs.getLong("id"), rs.getTimestamp("created_at").toInstant(),
					closed, due, "pending");
			}, matchingId);
		if (contexts.isEmpty()) {
			return Optional.empty();
		}
		Context context = contexts.getFirst();
		// Notice delivery updates its participant document independently, so stabilize child metadata too.
		jdbc.queryForList("select id from chat_participant where chat_id=? order by id for update",
			Long.class, context.chatId());
		jdbc.queryForList("select id from chat_safety_notice where chat_participant_id in "
			+ "(select id from chat_participant where chat_id=?) order by id for update", Long.class, context.chatId());
		// Freeze natural expiry at its original effective time, never at the worker's current time.
		if (!context.closedAt().isAfter(now)) {
			jdbc.update("update chat set closed_at=expires_at,close_reason='EXPIRED' "
				+ "where id=? and closed_at is null and expires_at<=?", context.chatId(), Timestamp.from(now));
		}
		// Report writers follow the same matching -> chat lock order before recording evidence.
		// These row locks protect this chat without blocking reports for every other room.
		Timestamp firstReport = jdbc.queryForObject("select min(created_at) from chat_report where chat_id=?",
			Timestamp.class, context.chatId());
		Instant due = context.dueAt();
		if (firstReport != null && firstReport.toInstant().plus(Duration.ofDays(90)).isAfter(due)) {
			due = firstReport.toInstant().plus(Duration.ofDays(90));
		}
		String metadata = jdbc.queryForObject("select jsonb_build_object('aiState',c.ai_state,"
			+ "'icebreakers',c.icebreakers,'notices',coalesce((select jsonb_agg("
			+ "jsonb_build_object('id',p.id,'state',p.notice_state) order by p.id) "
			+ "from chat_participant p where p.chat_id=c.id),'[]'::jsonb),"
			+ "'safetyNotices',coalesce((select jsonb_agg(jsonb_build_object('id',n.id,'type',n.notice_type,"
			+ "'createdAt',n.created_at,'displayedAt',n.displayed_at) order by n.id) from chat_safety_notice n "
			+ "join chat_participant p on p.id=n.chat_participant_id where p.chat_id=c.id),'[]'::jsonb))::text "
			+ "from chat c where c.id=?",
			String.class, context.chatId());
		return Optional.of(new Context(matchingId, context.chatId(), context.createdAt(), context.closedAt(), due,
			fingerprint(metadata)));
	}

	public Optional<ChatDeletionState> state(long matchingId) {
		List<String> rows = jdbc.queryForList("select chat_deletion_state::text from matching where id=?",
			String.class, matchingId);
		if (rows.isEmpty() || rows.getFirst() == null) {
			return Optional.empty();
		}
		return Optional.of(mapper.readValue(rows.getFirst(), ChatDeletionState.class));
	}

	public void save(long matchingId, ChatDeletionState state) {
		if (jdbc.update("update matching set chat_deletion_state=cast(? as jsonb) where id=?",
			mapper.writeValueAsString(state), matchingId) != 1) {
			throw new IllegalStateException("Chat deletion owner disappeared");
		}
	}

	public void deleteChat(Context context) {
		jdbc.update("delete from chat_safety_notice where chat_participant_id in "
			+ "(select id from chat_participant where chat_id=?)", context.chatId());
		jdbc.update("delete from chat_message where chat_id=?", context.chatId());
		jdbc.update("delete from chat_participant where chat_id=?", context.chatId());
		int deleted = jdbc.update("delete from chat where id=? and matching_id=? and created_at=? and closed_at=?",
			context.chatId(), context.matchingId(), Timestamp.from(context.createdAt()),
			Timestamp.from(context.closedAt()));
		if (deleted != 1) {
			throw new IllegalStateException("Chat deletion generation changed");
		}
	}

	private static String fingerprint(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}

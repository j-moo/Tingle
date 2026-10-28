package com.e106.tingle.chat.notice;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatMember;
import com.e106.tingle.chat.ChatMemberRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
@Transactional
public class ChatNoticeJobRepository {
	private final ChatMemberRepository participants;
	private final EntityManager entityManager;
	private final JdbcTemplate jdbc;

	public Optional<ChatNoticeJob> findByBusinessKey(String key) {
		String[] parts = key.split(":", -1);
		if (parts.length != 4 || !parts[0].equals("chat-notice") || !supported(parts[3])) {
			return Optional.empty();
		}
		return participants.findByChatRoomIdAndUserId(Long.parseLong(parts[1]), Long.parseLong(parts[2]))
			.map(member -> member.notice(parts[3])).map(ChatNoticeJob::restore);
	}

	public Optional<ChatNoticeJob> findByPublicId(UUID id) {
		return byPublicId(id, false);
	}

	public Optional<ChatNoticeJob> lockById(UUID id) {
		return byPublicId(id, true);
	}

	public ChatNoticeJob save(ChatNoticeJob job) {
		ChatMember member = participants.lockMember(job.roomId(), job.userId()).orElseThrow();
		entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);
		Map<String, Object> previous = member.notice(job.getJobType());
		if (previous != null) {
			return ChatNoticeJob.restore(previous);
		}
		// Serializing on the UUID preserves the previous global notice-ID uniqueness without a jobs table.
		jdbc.queryForObject("select pg_advisory_xact_lock(?)", (rs, row) -> Boolean.TRUE,
			job.getPublicId().getMostSignificantBits() ^ job.getPublicId().getLeastSignificantBits());
		Optional<ChatNoticeJob> existing = findByPublicId(job.getPublicId());
		if (existing.isPresent() && !existing.get().getBusinessKey().equals(job.getBusinessKey())) {
			throw new IllegalStateException("안내 UUID가 다른 영속 업무에 이미 사용 중입니다");
		}
		member.putNotice(job.getJobType(), job.document());
		return ChatNoticeJob.restore(member.notice(job.getJobType()));
	}

	public Optional<ChatNoticeJob> request(String userId, String key) {
		entityManager.flush();
		List<String> ids = jdbc.queryForList("select n.value->>'noticeId' from chat_participant p "
			+ "cross join lateral jsonb_each(p.notice_state) n where p.user_id=? "
			+ "and n.key in ('FIRST_ENTRY','END_NOTICE') "
			+ "and (n.value->'payload'->>'entryKey'=? or n.value->'payload'->>'ackKey'=?) limit 1",
			String.class, Long.parseLong(userId), key, key);
		return ids.isEmpty() ? Optional.empty() : findByPublicId(UUID.fromString(ids.getFirst()));
	}

	public Optional<ChatNoticeJob> claimable(Instant now) {
		entityManager.flush();
		List<Long> ids = jdbc.queryForList("select p.id from chat_participant p "
			+ "where (p.notice_state->'END_NOTICE'->>'status'='PENDING' "
			+ "and cast(p.notice_state->'END_NOTICE'->>'runAt' as timestamptz)<=?) "
			+ "or (p.notice_state->'END_NOTICE'->>'status'='RUNNING' "
			+ "and cast(p.notice_state->'END_NOTICE'->>'leaseUntil' as timestamptz)<=?) "
			+ "order by cast(p.notice_state->'END_NOTICE'->>'runAt' as timestamptz),p.id "
			+ "limit 1 for update skip locked", Long.class, Timestamp.from(now), Timestamp.from(now));
		if (ids.isEmpty()) {
			return Optional.empty();
		}
		ChatMember member = entityManager.find(ChatMember.class, ids.getFirst(), LockModeType.PESSIMISTIC_WRITE);
		entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);
		return Optional.ofNullable(member.notice("END_NOTICE")).map(ChatNoticeJob::restore);
	}

	private Optional<ChatNoticeJob> byPublicId(UUID id, boolean lock) {
		entityManager.flush();
		List<Long> ids = jdbc.queryForList("select id from chat_participant "
			+ "where notice_state->'FIRST_ENTRY'->>'noticeId'=? or notice_state->'END_NOTICE'->>'noticeId'=?",
			Long.class, id.toString(), id.toString());
		if (ids.isEmpty()) {
			return Optional.empty();
		}
		if (ids.size() != 1) {
			throw new IllegalStateException("안내 UUID의 소유 참여자가 여러 명입니다");
		}
		ChatMember member = lock ? entityManager.find(ChatMember.class, ids.getFirst(), LockModeType.PESSIMISTIC_WRITE)
			: entityManager.find(ChatMember.class, ids.getFirst());
		if (lock) {
			entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);
		}
		return List.of("FIRST_ENTRY", "END_NOTICE").stream().map(member::notice).filter(document -> document != null)
			.filter(document -> id.toString().equals(document.get("noticeId"))).findFirst().map(ChatNoticeJob::restore);
	}

	private boolean supported(String type) {
		return type.equals("FIRST_ENTRY") || type.equals("END_NOTICE");
	}
}

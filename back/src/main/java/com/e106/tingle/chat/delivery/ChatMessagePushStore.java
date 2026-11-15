package com.e106.tingle.chat.delivery;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;
import com.e106.tingle.push.PushDeliveryLookup;
import com.e106.tingle.push.PushDeliveryLookup.Candidate;
import com.e106.tingle.push.PushDeliveryTarget;
import com.e106.tingle.push.PushGateway;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class ChatMessagePushStore {
	private final JdbcTemplate jdbc;
	private final JsonMapper jsonMapper;
	private final ChatRoomRepository rooms;
	private final ChatService chats;
	private final PushDeliveryLookup deliveries;
	private final Clock clock;
	private final RestoreServingGateFilter servingGate;

	@Transactional
	public Claim claim() {
		if (!servingGate.servingAllowed()) {
			return null;
		}
		Instant now = clock.instant();
		List<Long> ids = jdbc.queryForList("select id from chat_message where "
			+ "(push_state->>'status'='PENDING' and cast(push_state->>'runAt' as timestamptz)<=?) "
			+ "or (push_state->>'status'='RUNNING' and cast(push_state->>'leaseUntil' as timestamptz)<=?) "
			+ "order by created_at,id limit 1 for update skip locked", Long.class,
			Timestamp.from(now), Timestamp.from(now));
		if (ids.isEmpty()) {
			return null;
		}
		Job job = load(ids.getFirst());
		if (job == null) {
			return null;
		}
		int attempt = Math.toIntExact(MessagePushState.number(job.state(), "attempts")) + 1;
		if (attempt > 5 || !now.isBefore(MessagePushState.instant(job.state(), "deadline"))) {
			finish(job, "FAILED", "RETRY_EXHAUSTED", now);
			return new Claim(job.id(), attempt);
		}
		job.state().put("status", "RUNNING");
		job.state().put("attempts", attempt);
		job.state().put("leaseUntil", now.plusSeconds(30).toString());
		save(job);
		return new Claim(job.id(), attempt);
	}

	@Transactional
	public List<Target> targets(Claim claim) {
		if (!servingGate.servingAllowed()) {
			return List.of();
		}
		Job job = current(claim);
		if (job == null) {
			return List.of();
		}
		ChatRoom room = eligibleRoom(job);
		if (room == null) {
			finish(job, "CANCELLED", "CHAT_INELIGIBLE", clock.instant());
			return List.of();
		}
		Instant deadline = deadline(job, room);
		// 알림 제목 · 본문(보낸 사람 닉네임 · 메시지 미리보기)은 보내는 순간에 읽는다. 차단 · 종료된 방은 위에서 이미 걸렀다.
		// 기록(push_state)에는 남기지 않고 FCM 데이터에만 싣는다. 본문은 저장할 때 주소 가리기를 마친 값이다
		Preview preview = preview(job.id());
		return MessagePushState.candidates(job.state()).stream()
			.filter(c -> !MessagePushState.results(job.state()).containsKey(c.bindingId().toString()))
			.map(c -> new Target(c, (String)job.state().get("eventId"),
				MessagePushState.payload(job.id(), room.getPublicId(), job.sequence(), job.state(), c, deadline,
					preview.title(), preview.body()),
				deadline)).toList();
	}

	@Transactional
	public Optional<PushDeliveryTarget> resolve(Claim claim, Target target) {
		if (!servingGate.servingAllowed()) {
			return Optional.empty();
		}
		Job job = current(claim);
		if (job == null || !target.eventId().equals(job.state().get("eventId"))
			|| !MessagePushState.candidates(job.state()).contains(target.candidate())
			|| MessagePushState.results(job.state()).containsKey(target.candidate().bindingId().toString())) {
			return Optional.empty();
		}
		ChatRoom room = eligibleRoom(job);
		if (room == null || !clock.instant().isBefore(target.deadline())
			|| !target.deadline().equals(deadline(job, room))) {
			return Optional.empty();
		}
		Optional<PushDeliveryTarget> resolved = deliveries.resolve(target.candidate());
		if (resolved.isPresent()) {
			// Each provider call has a 10-second timeout; renew before every target in a multi-device batch.
			job.state().put("leaseUntil", clock.instant().plusSeconds(30).toString());
			save(job);
		}
		return resolved;
	}

	@Transactional
	public void record(Claim claim, Target target, PushGateway.Result result) {
		Job job = current(claim);
		if (job == null || !MessagePushState.candidates(job.state()).contains(target.candidate())) {
			return;
		}
		if (result == PushGateway.Result.INVALID_TOKEN) {
			deliveries.invalidate(target.candidate());
		}
		if (result != PushGateway.Result.RETRY) {
			MessagePushState.results(job.state()).put(target.candidate().bindingId().toString(), result.name());
			save(job);
		}
	}

	@Transactional
	public void finish(Claim claim) {
		// Preserve the lease and results while serving is blocked; do not turn a restore gate into cancellation.
		if (!servingGate.servingAllowed()) {
			return;
		}
		Job job = current(claim);
		if (job == null) {
			return;
		}
		ChatRoom room = eligibleRoom(job);
		Instant now = clock.instant();
		if (room == null) {
			finish(job, "CANCELLED", "CHAT_INELIGIBLE", now);
			return;
		}
		boolean pending = MessagePushState.candidates(job.state()).stream()
			.anyMatch(c -> !MessagePushState.results(job.state()).containsKey(c.bindingId().toString())
				&& deliveries.resolve(c).isPresent());
		if (!pending) {
			finish(job, "SUCCEEDED", "PROCESSED", now);
		} else if (claim.attempt() >= 5 || !now.isBefore(deadline(job, room))) {
			finish(job, "FAILED", "RETRY_EXHAUSTED", now);
		} else {
			job.state().put("status", "PENDING");
			job.state().put("runAt", now.plusSeconds(30L << (claim.attempt() - 1)).toString());
			job.state().put("reason", "PROVIDER_RETRY");
			job.state().remove("leaseUntil");
			save(job);
		}
	}

	private ChatRoom eligibleRoom(Job job) {
		ChatRoom room = rooms.findById(job.roomId()).orElse(null);
		long recipientId = MessagePushState.number(job.state(), "recipientId");
		return room != null && clock.instant().isBefore(deadline(job, room))
			&& chats.available(room, recipientId) ? room : null;
	}

	private Instant deadline(Job job, ChatRoom room) {
		Instant notificationDeadline = MessagePushState.instant(job.state(), "deadline");
		return room.getExpiresAt().isBefore(notificationDeadline) ? room.getExpiresAt() : notificationDeadline;
	}

	private Job current(Claim claim) {
		Job job = load(claim.messageId());
		return job != null && "RUNNING".equals(job.state().get("status"))
			&& MessagePushState.number(job.state(), "attempts") == claim.attempt()
			&& clock.instant().isBefore(MessagePushState.instant(job.state(), "leaseUntil")) ? job : null;
	}

	@SuppressWarnings("unchecked")
	private Job load(long id) {
		List<Job> rows = jdbc.query("select id,chat_id,seq,push_state from chat_message where id=? for update",
			(rs, row) -> new Job(rs.getLong(1), rs.getLong(2), rs.getLong(3),
				jsonMapper.readValue(rs.getString(4), Map.class)), id);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private void save(Job job) {
		jdbc.update("update chat_message set push_state=cast(? as jsonb) where id=?",
			jsonMapper.writeValueAsString(job.state()), job.id());
	}

	private void finish(Job job, String state, String reason, Instant now) {
		MessagePushState.finish(job.state(), state, reason, now);
		save(job);
	}

	public record Claim(long messageId, int attempt) {
	}

	public record Target(Candidate candidate, String eventId, Map<String, String> data, Instant deadline) {
	}

	private record Job(long id, long roomId, long sequence, Map<String, Object> state) {
	}

	private Preview preview(long messageId) {
		return jdbc.query("select profile.nickname, m.body from chat_message m "
			+ "join chat_participant sender on sender.id=m.sender_participant_id "
			+ "left join profile on profile.user_id=sender.user_id and profile.birth_date is not null where m.id=?",
			rs -> rs.next() ? new Preview(blankToNull(rs.getString(1)), MessagePushState.preview(rs.getString(2)))
				: new Preview(null, null), messageId);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private record Preview(String title, String body) {
	}
}

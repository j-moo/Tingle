package com.e106.tingle.chat.notice;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatMember;
import com.e106.tingle.chat.ChatMemberRepository;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.i18n.Messages;
import com.e106.tingle.push.PushDeliveryLookup;
import com.e106.tingle.push.PushDeliveryTarget;
import com.e106.tingle.push.PushGateway;
import com.e106.tingle.push.PushNotificationKind;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NoticeDeliveryStore {
	private final ChatNoticeJobRepository jobs;
	private final ChatRoomRepository rooms;
	private final ChatMemberRepository members;
	private final ChatService chats;
	private final PushDeliveryLookup deliveries;
	private final Clock clock;
	private final JdbcTemplate jdbc;

	@Transactional
	public Claim claim() {
		ChatNoticeJob job = jobs.claimable(clock.instant()).orElse(null);
		if (job == null) {
			return null;
		}
		job.claim(clock.instant());
		return new Claim(job.getId(), job.getAttempts());
	}

	@Transactional
	public List<Target> targets(Claim claim) {
		ChatNoticeJob job = current(claim);
		if (job == null) {
			return List.of();
		}
		ChatRoom room = rooms.findById(job.roomId()).orElse(null);
		if (room == null || !chats.available(room, job.userId())) {
			job.finish("CANCELLED", "CHAT_INELIGIBLE", clock.instant());
			return List.of();
		}
		ChatMember member = members.findByChatRoomIdAndUserId(room.getId(), job.userId()).orElse(null);
		if (member == null || member.getEndNoticeAckedAt() != null) {
			job.finish("SUCCEEDED", "DISPLAY_ACKED", clock.instant());
			return List.of();
		}
		List<Target> result = deliveries.candidates(job.userId(), PushNotificationKind.CHAT_END_NOTICE).stream()
			.filter(i -> i.bindingId() != null && i.linkedAt() != null
				&& !i.linkedAt().isAfter(room.getExpiresAt().minusSeconds(3600)))
			.filter(i -> !job.alreadyDelivered(i.bindingId().toString()))
			.map(i -> target(job, room, i)).toList();
		if (result.isEmpty()) {
			job.finish("SUCCEEDED", "NO_PENDING_LOGIN_TARGET", clock.instant());
		}
		return result;
	}

	@Transactional
	public boolean validate(Claim claim, Target target) {
		return resolve(claim, target).isPresent();
	}

	@Transactional
	public Optional<PushDeliveryTarget> resolve(Claim claim, Target target) {
		ChatNoticeJob job = current(claim);
		ChatRoom room = job == null ? null : rooms.findById(job.roomId()).orElse(null);
		ChatMember member = job == null ? null
			: members.findByChatRoomIdAndUserId(job.roomId(), job.userId()).orElse(null);
		if (job == null || room == null || member == null || member.getEndNoticeAckedAt() != null
			|| target.candidate().userId() != job.userId()
			|| target.candidate().kind() != PushNotificationKind.CHAT_END_NOTICE
			|| !job.getPublicId().toString().equals(target.noticeId())
			|| target.candidate().linkedAt() == null
			|| target.candidate().linkedAt().isAfter(room.getExpiresAt().minusSeconds(3600))
			|| job.alreadyDelivered(target.bindingId().toString()) || !chats.available(room, job.userId())) {
			return Optional.empty();
		}
		return deliveries.resolve(target.candidate());
	}

	@Transactional
	public boolean record(Claim claim, Target target, PushGateway.Result result) {
		ChatNoticeJob job = current(claim);
		if (job == null) {
			return false;
		}
		if (result == PushGateway.Result.INVALID_TOKEN && !deliveries.invalidate(target.candidate())) {
			return true;
		}
		if (result == PushGateway.Result.ACCEPTED || result == PushGateway.Result.REJECTED
			|| result == PushGateway.Result.INVALID_TOKEN) {
			job.delivered(target.bindingId().toString(), result.name());
		}
		return result == PushGateway.Result.RETRY;
	}

	@Transactional
	public void finish(Claim claim, boolean retry) {
		ChatNoticeJob job = current(claim);
		if (job != null) {
			if (retry) {
				job.retry("PROVIDER_RETRY", clock.instant());
			} else {
				job.finish("SUCCEEDED", null, clock.instant());
			}
		}
	}

	private ChatNoticeJob current(Claim claim) {
		ChatNoticeJob job = jobs.lockById(claim.jobId()).orElse(null);
		return job != null && job.getStatus().equals("RUNNING") && job.getAttempts() == claim.attempt()
			&& job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(clock.instant()) ? job : null;
	}

	private Target target(ChatNoticeJob job, ChatRoom room, PushDeliveryLookup.Candidate candidate) {
		// 제목은 대화 상대 닉네임(없으면 "대화 종료 안내"). 이 안내는 대화 중인 상대에 대한 것이라 닉네임을 넣어도 된다
		String partner = jdbc.query("select profile.nickname from chat_participant p "
			+ "left join profile on profile.user_id=p.user_id "
			+ "where p.chat_id=? and p.user_id is not null and p.user_id<>?",
			rs -> rs.next() ? rs.getString(1) : null, room.getId(), job.userId());
		Map<String, String> data = Map.of("noticeId", job.getPublicId().toString(),
			"chatRoomId", room.getPublicId().toString(), "bindingId", candidate.bindingId().toString(),
			"installationId", candidate.installationId().toString(), "type", "ONE_HOUR_BEFORE_END",
			"expiresAt", room.getExpiresAt().toString(),
			"title", partner == null || partner.isBlank() ? Messages.get("push.chatEnding.title") : partner,
			"body", Messages.get("push.chatEnding.body"));
		return new Target(candidate, job.getPublicId().toString(), data, room.getExpiresAt());
	}

	public record Claim(UUID jobId, int attempt) {
	}

	public record Target(PushDeliveryLookup.Candidate candidate, String noticeId,
		Map<String, String> data, Instant deadline) {
		public UUID bindingId() {
			return candidate.bindingId();
		}
	}
}

package com.e106.tingle.chat.notice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatMember;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.dto.EntryView;
import com.e106.tingle.chat.dto.NoticeAck;
import com.e106.tingle.chat.dto.NoticeView;
import com.e106.tingle.chat.dto.NoticesView;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.i18n.Messages;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatNoticeService {
	private final ChatService chats;
	private final ChatNoticeJobs store;
	private final ChatNoticeJobRepository jobs;
	private final Clock clock;

	@Transactional
	public EntryView enter(UUID roomId, long userId, UUID key) {
		ChatService.NoticeAccess access = chats.noticeAccess(roomId, userId, true, false);
		ChatNoticeJob first = store.ensure(access.room(), userId, "FIRST_ENTRY", clock.instant());
		bind(first, userId, key, "entry");
		return new EntryView(chats.room(roomId, userId), pending(access, first));
	}

	@Transactional
	public NoticesView list(UUID roomId, long userId) {
		ChatService.NoticeAccess access = chats.noticeAccess(roomId, userId, true, false);
		ChatNoticeJob first = jobs.findByBusinessKey(ChatNoticeJob.key(access.room().getId(), userId, "FIRST_ENTRY"))
			.orElse(null);
		return new NoticesView(pending(access, first));
	}

	@Transactional
	public NoticeAck ack(UUID roomId, UUID noticeId, long userId, UUID key) {
		ChatService.NoticeAccess access = chats.noticeAccess(roomId, userId, true, true);
		ChatNoticeJob job = jobs.findByPublicId(noticeId).orElseThrow(this::notFound);
		if ((!job.getJobType().equals("FIRST_ENTRY") && !job.getJobType().equals("END_NOTICE"))
			|| job.userId() != userId || job.roomId() != access.room().getId()) {
			throw notFound();
		}
		boolean first = job.getJobType().equals("FIRST_ENTRY");
		if (!first && (!job.getJobType().equals("END_NOTICE")
			|| clock.instant().isBefore(access.room().getExpiresAt().minusSeconds(3600)))) {
			throw new BusinessException(ErrorCode.NOTICE_STATE_CONFLICT);
		}
		bind(job, userId, key, "ack");
		access.member().ackNotice(first, clock.instant());
		return new NoticeAck(clock.instant());
	}

	private List<NoticeView> pending(ChatService.NoticeAccess access, ChatNoticeJob first) {
		List<NoticeView> result = new ArrayList<>();
		if (first != null && access.member().getFirstNoticeAckedAt() == null) {
			result.add(view(first, access.room()));
		}
		if (!clock.instant().isBefore(access.room().getExpiresAt().minusSeconds(3600))
			&& access.member().getEndNoticeAckedAt() == null) {
			result.add(view(store.ensure(access.room(), access.member().getUserId(), "END_NOTICE", clock.instant()),
				access.room()));
		}
		return result;
	}

	private NoticeView view(ChatNoticeJob job, ChatRoom room) {
		boolean first = job.getJobType().equals("FIRST_ENTRY");
		String end = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
			.format(room.getExpiresAt());
		long minutes = Math.max(1, (Duration.between(clock.instant(), room.getExpiresAt()).getSeconds() + 59) / 60);
		String text = first ? Messages.get("notice.first", end) : Messages.get("notice.end", end, minutes);
		return new NoticeView(job.getPublicId(), first ? "FIRST_ENTRY" : "ONE_HOUR_BEFORE_END",
			first ? job.getRunAt() : room.getExpiresAt().minusSeconds(3600), room.getExpiresAt(), text, null);
	}

	private void bind(ChatNoticeJob job, long userId, UUID key, String operation) {
		jobs.request(Long.toString(userId), key.toString()).ifPresent(previous -> {
			if (!previous.getId().equals(job.getId())
				|| !key.toString().equals(previous.getPayload().get(operation + "Key"))) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
		});
		job.bind(operation, key);
	}

	private BusinessException notFound() {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
	}
}

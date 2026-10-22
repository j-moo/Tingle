package com.e106.tingle.chat.notice;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatRoom;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatNoticeJobs {
	private final ChatNoticeJobRepository jobs;

	@Transactional(propagation = Propagation.MANDATORY)
	public ChatNoticeJob ensure(ChatRoom room, long userId, String type, Instant now) {
		return jobs.findByBusinessKey(ChatNoticeJob.key(room.getId(), userId, type)).orElseGet(() -> jobs.save(
			new ChatNoticeJob(room.getId(), userId, type,
				type.equals("END_NOTICE") ? room.getExpiresAt().minusSeconds(3600) : now)));
	}
}

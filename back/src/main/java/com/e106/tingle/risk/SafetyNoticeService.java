package com.e106.tingle.risk;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.dto.SafetyNoticeDisplayed;
import com.e106.tingle.risk.dto.SafetyNoticeView;
import com.e106.tingle.risk.dto.SafetyNotices;

import lombok.RequiredArgsConstructor;

/**
 * AI 위험 안내(chat_safety_notice)의 본인 조회와 표시 확인. 안내는 받은 사람 본인에게만 보인다.
 * 방 이용 권한(활성 방 · 나가지 않음 · 차단/AI 동의 철회 없음)은 채팅과 같은 기준으로 확인한다.
 */
@Service
@RequiredArgsConstructor
public class SafetyNoticeService {

	private final ChatService chats;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	@Transactional(readOnly = true)
	public SafetyNotices list(long roomId, long userId) {
		long participant = participant(roomId, userId, false);
		List<SafetyNoticeView> items = jdbc.query("select id,notice_type::text,audience,created_at "
			+ "from chat_safety_notice where chat_participant_id=? and displayed_at is null order by created_at,id",
			(rs, row) -> new SafetyNoticeView(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getTimestamp(4).toInstant()), participant);
		return new SafetyNotices(items);
	}

	/**
	 * 사용자가 확인한(앱에서 "안내 닫기"를 누른) 안내를 displayed_at 에 기록한다. 확인 전에는 다시 조회해도 계속 보인다.
	 * 같은 안내를 다시 확인해도 처음 시각을 유지한다.
	 */
	@Transactional
	public SafetyNoticeDisplayed displayed(long roomId, long noticeId, long userId) {
		if (noticeId < 1) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		long participant = participant(roomId, userId, true);
		List<Instant> shown = jdbc.query("update chat_safety_notice set displayed_at=coalesce(displayed_at,?) "
			+ "where id=? and chat_participant_id=? returning displayed_at",
			(rs, row) -> rs.getTimestamp(1).toInstant(), Timestamp.from(clock.instant()), noticeId, participant);
		if (shown.isEmpty()) {
			// 남의 안내·없는 안내는 같은 응답으로 숨긴다
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return new SafetyNoticeDisplayed(noticeId, shown.getFirst());
	}

	private long participant(long roomId, long userId, boolean lock) {
		if (roomId < 1) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return chats.noticeAccess(roomId, userId, lock, false).member().getId();
	}
}

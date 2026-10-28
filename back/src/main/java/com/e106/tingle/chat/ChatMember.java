package com.e106.tingle.chat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_participant", uniqueConstraints = @UniqueConstraint(
	name = "uq_chat_participant_member", columnNames = {"chat_id", "user_id"}),
	indexes = @Index(name = "ix_chat_participant_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMember {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "chat_id", nullable = false)
	private Long chatRoomId;
	private Long userId;
	private Instant leftAt;
	@Column(nullable = false)
	@ColumnDefault("0")
	private long lastReadMessageSeq;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	@ColumnDefault("'{}'::jsonb")
	private Map<String, Object> noticeState = new HashMap<>();
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	@ColumnDefault("'{}'::jsonb")
	private Map<String, Object> readState = new HashMap<>();

	public ChatMember(long roomId, long userId) {
		this.chatRoomId = roomId;
		this.userId = userId;
	}

	public Instant getFirstNoticeAckedAt() {
		return noticeAcknowledgedAt("FIRST_ENTRY");
	}

	public Instant getEndNoticeAckedAt() {
		return noticeAcknowledgedAt("END_NOTICE");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> notice(String type) {
		Object document = noticeState.get(type);
		return document instanceof Map<?, ?> ? (Map<String, Object>)document : null;
	}

	public void putNotice(String type, Map<String, Object> document) {
		noticeState.put(type, document);
	}

	public void ackNotice(boolean first, Instant now) {
		Map<String, Object> document = notice(first ? "FIRST_ENTRY" : "END_NOTICE");
		if (document == null) {
			throw new IllegalStateException("확인할 안내의 영속 증거가 없습니다");
		}
		document.putIfAbsent("ackedAt", now.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
	}

	public Instant readAt(long messageId) {
		Object receipt = readState.get(Long.toString(messageId));
		if (receipt instanceof Map<?, ?> document && document.get("readAt") instanceof String at) {
			return Instant.parse(at);
		}
		return null;
	}

	public void recordRead(long messageId, Instant now) {
		readState.putIfAbsent(Long.toString(messageId), new HashMap<>(Map.of("readAt",
			now.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString())));
	}

	public void advanceReadSequence(long sequence) {
		if (sequence > lastReadMessageSeq) {
			lastReadMessageSeq = sequence;
		}
	}

	private Instant noticeAcknowledgedAt(String type) {
		Map<String, Object> document = notice(type);
		return document != null && document.get("ackedAt") instanceof String at ? Instant.parse(at) : null;
	}
}

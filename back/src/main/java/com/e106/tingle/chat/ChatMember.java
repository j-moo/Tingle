package com.e106.tingle.chat;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_members")
@IdClass(ChatMember.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMember {
	@Id
	private Long chatRoomId;
	@Id
	private Long userId;
	private Instant leftAt;
	private Instant firstNoticeAckedAt;
	private Instant endNoticeAckedAt;

	public ChatMember(long roomId, long userId) {
		this.chatRoomId = roomId;
		this.userId = userId;
	}

	public void ackNotice(boolean first, Instant now) {
		Instant at = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		if (first && firstNoticeAckedAt == null) {
			firstNoticeAckedAt = at;
		} else if (!first && endNoticeAckedAt == null) {
			endNoticeAckedAt = at;
		}
	}

	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	public static class Key implements Serializable {
		private Long chatRoomId;
		private Long userId;
	}
}

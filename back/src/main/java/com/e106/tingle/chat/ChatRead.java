package com.e106.tingle.chat;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_message_reads", uniqueConstraints = @UniqueConstraint(
	name = "uq_chat_message_reads_1", columnNames = {"message_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRead {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "receipt_id")
	private Long id;
	@Column(nullable = false)
	private Long messageId;
	@Column(nullable = false)
	private Long userId;
	@Column(nullable = false)
	private Instant readAt;

	public ChatRead(long messageId, long userId, Instant now) {
		this.messageId = messageId;
		this.userId = userId;
		this.readAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
	}
}

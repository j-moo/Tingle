package com.e106.tingle.chat;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * canonical chat_safety_notice의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "chat_safety_notice", indexes = {
	@Index(name = "uq_chat_safety_notice_once", columnList = "chat_participant_id,notice_type", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSafetyNotice {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "chat_participant_id", nullable = false)
	private Long chatParticipantId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "notice_type", nullable = false, columnDefinition = "safety_notice_type")
	private SafetyNoticeType noticeType;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "displayed_at")
	private Instant displayedAt;

	public enum SafetyNoticeType {
		MONEY_REQUEST,
		PERSONAL_INFO,
		OFF_PLATFORM,
		HARASSMENT
	}
}

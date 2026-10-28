package com.e106.tingle.report;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * canonical chat_report_message의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "chat_report_message")
@IdClass(ChatReportMessage.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatReportMessage {
	@Id
	@Column(name = "report_id", nullable = false)
	private Long reportId;

	@Id
	@Column(name = "message_id", nullable = false)
	private Long messageId;

	@Getter
	@NoArgsConstructor
	@EqualsAndHashCode
	public static class Key implements Serializable {
		private static final long serialVersionUID = 1L;
		private Long reportId;
		private Long messageId;

		public Key(Long reportId, Long messageId) {
			this.reportId = reportId;
			this.messageId = messageId;
		}
	}
}

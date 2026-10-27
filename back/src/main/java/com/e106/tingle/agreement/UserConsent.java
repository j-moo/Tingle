package com.e106.tingle.agreement;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 동의 기록 (ERD user_consents). 고치지 않고 쌓기만 한다.
 * 동의하면 agreed = true, 거절·철회하면 agreed = false 인 행이 새로 생긴다. 항목별로 가장 최근 행이 현재 상태다.
 */
@Entity
@Table(name = "user_consents", indexes = @Index(name = "ix_user_consents_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "consent_id")
	private Long id;

	@Column(nullable = false)
	private Long userId;

	// 동의 항목 종류 (app.agreement.items 의 type)
	@Column(nullable = false, columnDefinition = "text")
	private String consentType;

	// 사용자가 확인한 정확한 문서 버전
	@Column(nullable = false, columnDefinition = "text")
	private String documentVersion;

	@Column(nullable = false)
	private boolean agreed;

	@Column(nullable = false)
	private Instant recordedAt;

	public UserConsent(Long userId, String consentType, String documentVersion, boolean agreed, Instant recordedAt) {
		this.userId = userId;
		this.consentType = consentType;
		this.documentVersion = documentVersion;
		this.agreed = agreed;
		this.recordedAt = recordedAt;
	}
}

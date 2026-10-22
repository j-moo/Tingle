package com.e106.tingle.push;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ERD push_installations. 공개 설치 UUID와 내부 저장 ID를 구분한다.
 * 등록·교체는 소유권 조건을 포함한 원자적 SQL로 처리한다.
 */
@Entity
@Table(name = "push_installations", uniqueConstraints = @UniqueConstraint(
	name = "uq_push_installations_1", columnNames = "client_installation_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushInstallation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "installation_id")
	private Long id;

	@Column(nullable = false)
	private UUID clientInstallationId;

	@Column(nullable = false)
	private Long userId;

	// null인 기존 행은 로그인 중이라고 가정하지 않는다. 새 제안 알림 연결만 유지한다.
	private UUID loginFamilyId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "text")
	private PushPlatform platform;

	@Column(nullable = false, columnDefinition = "text")
	private String tokenCiphertext;

	@Column(nullable = false)
	private Instant updatedAt;
}

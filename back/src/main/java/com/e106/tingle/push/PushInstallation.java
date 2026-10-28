package com.e106.tingle.push;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * ERD push_installation. 공개 설치 UUID와 내부 저장 ID를 구분한다.
 * 등록·교체는 소유권 조건을 포함한 원자적 SQL로 처리한다.
 */
@Entity
@Table(name = "push_installation", uniqueConstraints = @UniqueConstraint(
	name = "uq_push_installation_client_installation_id", columnNames = "client_installation_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushInstallation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(nullable = false)
	private UUID clientInstallationId;

	private Long userId;

	// 연결 증거가 없는 기존 행은 로그인 중이라고 가정하거나 값을 추정하지 않는다.
	private UUID loginFamilyId;

	private UUID bindingId;

	private Instant linkedAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "device_platform")
	private PushPlatform platform;

	@Column(nullable = false, columnDefinition = "text")
	private String tokenCiphertext;

	@Column(nullable = false)
	private Instant updatedAt;
}

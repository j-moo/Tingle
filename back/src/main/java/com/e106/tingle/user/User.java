package com.e106.tingle.user;

import java.time.Instant;

import com.e106.tingle.auth.SocialProvider;

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
 * 회원 계정 (ERD app_users). 소셜 로그인 때 만들어진다(UserRepository.insertIfAbsent).
 * 가입 때 입력한 정보는 Profile 에, 동의 기록은 UserConsent 에 따로 있다.
 */
@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(
	name = "uq_app_users_provider_subject", columnNames = {"provider", "provider_subject"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private SocialProvider provider;

	// 서버가 검증한 소셜 계정 고유 ID
	@Column(nullable = false)
	private String providerSubject;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	// 처음으로 모든 가입 단계를 마친(READY) 시각. 한 번 채워지면 바꾸지 않는다.
	private Instant signupCompletedAt;

	// ERD에 없는 컬럼(추가 요청 중). 연락용이며 회원 식별에 쓰지 않는다. 다른 소셜 계정이 같은 이메일을 가질 수 있어 유니크가 아니다.
	// 개인정보 수집 동의(PRIVACY)를 한 회원만 소셜이 확인한 이메일을 로그인 때 저장한다. 응답에 넣지 않는다.
	@Column(length = 320)
	private String email;
}

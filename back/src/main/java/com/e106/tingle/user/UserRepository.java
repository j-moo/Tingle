package com.e106.tingle.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.SocialProvider;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByProviderAndProviderSubject(SocialProvider provider, String providerSubject);

	/**
	 * 회원 행을 잠그고 조회한다. 같은 회원의 약관 동의·프로필 저장·토큰 재발급·로그아웃을 한 번에 하나씩 처리하기 위해 쓴다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(Long id);

	/**
	 * 같은 소셜 계정으로 처음 로그인하는 요청이 동시에 와도 회원이 하나만 생기도록,
	 * 이미 있으면 아무것도 하지 않는다. 이후 findByProviderAndProviderSubject 로 조회한다.
	 */
	@Transactional
	@Modifying
	@Query(value = """
		INSERT INTO users (provider, provider_user_id, status, created_at, is_operator)
		VALUES (CAST(:provider AS auth_provider), :providerSubject, 'ACTIVE', :createdAt, false)
		ON CONFLICT (provider, provider_user_id) DO NOTHING
		""", nativeQuery = true)
	void insertIfAbsent(String provider, String providerSubject, Instant createdAt);

	@Transactional
	@Modifying
	@Query("UPDATE User u SET u.email = :email WHERE u.id = :id")
	void updateEmail(Long id, String email);

	// 처음 가입을 마친 시각만 남긴다(이미 있으면 그대로).
	@Modifying
	@Query("UPDATE User u SET u.signupCompletedAt = :now WHERE u.id = :id AND u.signupCompletedAt IS NULL")
	void markSignupCompleted(Long id, Instant now);
}

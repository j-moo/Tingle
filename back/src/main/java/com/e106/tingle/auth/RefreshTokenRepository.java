package com.e106.tingle.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	// 재발급은 회원 행 잠금(UserRepository.findByIdForUpdate)으로 직렬화하므로, 잠그기 전에 회원번호만 먼저 찾는다.
	@Query("select t.userId from RefreshToken t where t.tokenHash = :tokenHash")
	Optional<Long> findUserIdByTokenHash(byte[] tokenHash);

	Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

	// 세션(family)이 살아 있고 회원이 정지·탈퇴 상태가 아닌지. 액세스 토큰 검증·재발급마다 호출한다.
	@Query("SELECT COUNT(t) > 0 FROM RefreshToken t, User u WHERE u.id = t.userId AND t.familyId = :familyId"
		+ " AND t.userId = :userId AND t.revokedAt IS NULL AND u.status = com.e106.tingle.user.UserStatus.ACTIVE")
	boolean isSessionActive(UUID familyId, Long userId);

	// 이 토큰보다 나중에 발급됐고 아직 폐기되지 않은 같은 세션의 토큰 (응답 유실 재시도 판단용)
	List<RefreshToken> findByFamilyIdAndIdGreaterThanAndRevokedAtIsNull(UUID familyId, Long id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
	void revokeAllByUserId(Long userId, Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
	void revokeAllByFamilyId(UUID familyId, Instant now);
}

package com.e106.tingle.push;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PushInstallationRepository extends JpaRepository<PushInstallation, Long> {

	List<PushInstallation> findByUserIdOrderById(long userId);

	/**
	 * UUID가 같아도 현재 회원의 행만 갱신한다. 서로 다른 회원의 동시 최초 등록도 UNIQUE 제약으로 중재한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO push_installations
		(client_installation_id, user_id, login_family_id, platform, token_ciphertext, updated_at)
		VALUES (:installationId, :userId, :familyId, :platform, :ciphertext, :now)
		ON CONFLICT (client_installation_id) DO UPDATE
		SET platform = EXCLUDED.platform, token_ciphertext = EXCLUDED.token_ciphertext,
		updated_at = EXCLUDED.updated_at, login_family_id = EXCLUDED.login_family_id
		WHERE push_installations.user_id = :userId
		""", nativeQuery = true)
	int register(UUID installationId, long userId, UUID familyId, String platform, String ciphertext, Instant now);

	@Modifying
	@Query(value = """
		DELETE FROM push_installations WHERE client_installation_id = :installationId AND user_id = :userId
		""", nativeQuery = true)
	int removeOwned(UUID installationId, long userId);

	@Modifying
	@Query(value = """
		DELETE FROM push_installations
		WHERE client_installation_id = :installationId AND user_id = :userId AND login_family_id = :familyId
		""", nativeQuery = true)
	int releaseForSession(UUID installationId, long userId, UUID familyId);
}

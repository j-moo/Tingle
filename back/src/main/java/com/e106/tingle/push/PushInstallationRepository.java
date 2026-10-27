package com.e106.tingle.push;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PushInstallationRepository extends JpaRepository<PushInstallation, Long> {

	List<PushInstallation> findByUserIdOrderById(long userId);

	Optional<PushInstallation> findByClientInstallationIdAndUserId(UUID installationId, long userId);

	/**
	 * UUID가 같아도 현재 회원의 행만 갱신한다. 서로 다른 회원의 동시 최초 등록도 UNIQUE 제약으로 중재한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO push_installations
		(client_installation_id, user_id, login_family_id, platform, token_ciphertext,
		updated_at, binding_id, linked_at)
		VALUES (:installationId, :userId, :familyId, :platform, :ciphertext, :now, :bindingId, :now)
		ON CONFLICT (client_installation_id) DO UPDATE
		SET platform = EXCLUDED.platform, token_ciphertext = EXCLUDED.token_ciphertext,
		updated_at = EXCLUDED.updated_at,
		binding_id = CASE WHEN push_installations.login_family_id = EXCLUDED.login_family_id
		THEN COALESCE(push_installations.binding_id, EXCLUDED.binding_id) ELSE EXCLUDED.binding_id END,
		linked_at = CASE WHEN push_installations.login_family_id = EXCLUDED.login_family_id
		THEN COALESCE(push_installations.linked_at, EXCLUDED.linked_at) ELSE EXCLUDED.linked_at END,
		login_family_id = EXCLUDED.login_family_id
		WHERE push_installations.user_id = :userId
		""", nativeQuery = true)
	int register(UUID installationId, long userId, UUID familyId, String platform, String ciphertext,
		Instant now, UUID bindingId);

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

	@Modifying
	@Query(value = """
		DELETE FROM push_installations WHERE installation_id = :id
		AND binding_id = :bindingId AND token_ciphertext = :ciphertext
		""", nativeQuery = true)
	int removeRejected(long id, UUID bindingId, String ciphertext);
}

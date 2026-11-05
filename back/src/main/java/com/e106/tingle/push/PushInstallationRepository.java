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
	 * 현재 회원 또는 명시적으로 소유 연결이 비워진 행만 갱신한다. 같은 UUID의 동시 등록은 UNIQUE로 중재한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO push_installation
		(client_installation_id, user_id, login_family_id, platform, token_ciphertext,
		updated_at, binding_id, linked_at)
		VALUES (:installationId, :userId, :familyId, CAST(:platform AS device_platform),
		:ciphertext, :now, :bindingId, :now)
		ON CONFLICT (client_installation_id) DO UPDATE
		SET platform = EXCLUDED.platform, token_ciphertext = EXCLUDED.token_ciphertext,
		updated_at = EXCLUDED.updated_at,
		binding_id = CASE WHEN push_installation.user_id = EXCLUDED.user_id
		AND push_installation.login_family_id = EXCLUDED.login_family_id
		AND push_installation.binding_id IS NOT NULL AND push_installation.linked_at IS NOT NULL
		THEN push_installation.binding_id ELSE EXCLUDED.binding_id END,
		linked_at = CASE WHEN push_installation.user_id = EXCLUDED.user_id
		AND push_installation.login_family_id = EXCLUDED.login_family_id
		AND push_installation.binding_id IS NOT NULL AND push_installation.linked_at IS NOT NULL
		THEN push_installation.linked_at ELSE EXCLUDED.linked_at END,
		user_id = EXCLUDED.user_id, login_family_id = EXCLUDED.login_family_id
		WHERE push_installation.user_id IS NULL OR push_installation.user_id = :userId
		""", nativeQuery = true)
	int register(UUID installationId, long userId, UUID familyId, String platform, String ciphertext,
		Instant now, UUID bindingId);

	@Modifying
	@Query(value = """
		DELETE FROM push_installation WHERE client_installation_id = :installationId AND user_id = :userId
		""", nativeQuery = true)
	int removeOwned(UUID installationId, long userId);

	@Modifying
	@Query(value = """
		UPDATE push_installation SET user_id = NULL, login_family_id = NULL, binding_id = NULL, linked_at = NULL
		WHERE client_installation_id = :installationId AND user_id = :userId AND login_family_id = :familyId
		""", nativeQuery = true)
	int releaseForSession(UUID installationId, long userId, UUID familyId);

	@Modifying
	@Query(value = """
		UPDATE push_installation SET user_id = NULL, login_family_id = NULL,
		binding_id = NULL, linked_at = NULL, updated_at = :now
		WHERE user_id = :userId AND login_family_id = :familyId
		""", nativeQuery = true)
	int clearSessionConnections(long userId, UUID familyId, Instant now);

	@Modifying
	@Query(value = """
		UPDATE push_installation SET user_id = NULL, login_family_id = NULL,
		binding_id = NULL, linked_at = NULL, updated_at = :now
		WHERE user_id = :userId
		""", nativeQuery = true)
	int clearUserConnections(long userId, Instant now);

	@Modifying
	@Query(value = """
		DELETE FROM push_installation WHERE id = :id
		AND binding_id = :bindingId AND token_ciphertext = :ciphertext
		""", nativeQuery = true)
	int removeRejected(long id, UUID bindingId, String ciphertext);
}

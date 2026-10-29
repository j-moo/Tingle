package com.e106.tingle.keyring;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface KeyringSyncRunRepository extends JpaRepository<KeyringSyncRun, Long> {
	@Query(value = """
		select * from keyring_sync_run where keyring_id=:deviceId and owner_generation=:generation
		and (request_key=:requestKey or coalesce(request_key_aliases, '[]'::jsonb) @> cast(:alias as jsonb))
		""", nativeQuery = true)
	Optional<KeyringSyncRun> findRequest(long deviceId, UUID generation, UUID requestKey, String alias);

	Optional<KeyringSyncRun> findByKeyringIdAndOwnerGenerationAndSettingsVersion(
		long keyringId, UUID ownerGeneration, long settingsVersion);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from KeyringSyncRun r where r.syncRunId=:runId and r.keyringId=:deviceId")
	Optional<KeyringSyncRun> findForUpdate(long deviceId, UUID runId);
}

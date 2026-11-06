package com.e106.tingle.keyring;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface KeyringSyncRunRepository extends JpaRepository<KeyringSyncRun, Long> {
	// TPS1 tag 0x40의 프로필 종교 코드는 원본의 185번째 바이트다. 0은 값 없음이다.
	@Query(value = """
		select exists(select 1 from keyring_sync_run where owner_user_id=:userId
		and payload_format='tingle-profile-storage-v1' and settings_version=:version
		and substring(payload from 185 for 1) > decode('00','hex'))
		""", nativeQuery = true)
	boolean hasProfileReligionAtVersion(long userId, long version);

	@Modifying
	@Query(value = """
		delete from keyring_sync_run where owner_user_id=:userId
		and payload_format='tingle-profile-storage-v1'
		and substring(payload from 185 for 1) > decode('00','hex')
		""", nativeQuery = true)
	void deleteProfileReligionRuns(long userId);

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

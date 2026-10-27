package com.e106.tingle.chat.notice;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ChatNoticeJobRepository extends JpaRepository<ChatNoticeJob, Long> {
	Optional<ChatNoticeJob> findByBusinessKey(String key);

	Optional<ChatNoticeJob> findByPublicId(UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from ChatNoticeJob j where j.id = :id")
	Optional<ChatNoticeJob> lockById(@Param("id") long id);

	@Query(value = "select * from background_jobs where job_type in ('FIRST_ENTRY','END_NOTICE') "
		+ "and payload->>'userId'=:userId and (payload->>'entryKey'=:key or payload->>'ackKey'=:key) limit 1",
		nativeQuery = true)
	Optional<ChatNoticeJob> request(@Param("userId") String userId, @Param("key") String key);

	@Query(value = "select * from background_jobs where job_type='END_NOTICE' "
		+ "and ((status='PENDING' and run_at<=:now) or (status='RUNNING' and lease_until<=:now)) "
		+ "order by run_at,job_id limit 1 for update skip locked", nativeQuery = true)
	Optional<ChatNoticeJob> claimable(@Param("now") Instant now);
}

package com.e106.tingle.personality;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PersonalityRepository extends JpaRepository<Personality, Long> {

	Optional<Personality> findBySurveyIdAndAnalysisVersion(Long surveyId, String analysisVersion);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Personality p where p.id = :id")
	Optional<Personality> findByIdForUpdate(Long id);

	/**
	 * 지금 실행할 분석 한 줄을 잠근다. 새 요청(PENDING, nextRetryAt 없음), 선점 뒤 멈춘 PENDING(nextRetryAt 지남),
	 * 재시도를 기다리는 FAILED(nextRetryAt 지남)가 대상이다. 다른 서버가 잡은 줄은 건너뛴다.
	 */
	@Query(value = """
		SELECT * FROM personality
		WHERE (status = 'PENDING' AND (result ->> 'nextRetryAt' IS NULL
				OR (result ->> 'nextRetryAt')::timestamptz <= :now))
			OR (status = 'FAILED' AND (result ->> 'nextRetryAt')::timestamptz <= :now)
		ORDER BY id
		LIMIT 1
		FOR UPDATE SKIP LOCKED
		""", nativeQuery = true)
	Optional<Personality> lockNextDue(Instant now);
}

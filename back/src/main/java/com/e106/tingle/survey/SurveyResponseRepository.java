package com.e106.tingle.survey;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {

	Optional<SurveyResponse> findByIdAndUserId(Long id, Long userId);

	Optional<SurveyResponse> findByUserIdAndClientRequestId(Long userId, UUID clientRequestId);

	Optional<SurveyResponse> findByUserIdAndVersionCodeAndCompletedAtIsNull(Long userId, String versionCode);

	// 응답 저장이 아니므로 revision(ETag)은 올리지 않는다(JPQL 일괄 수정은 @Version 을 건드리지 않음).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update SurveyResponse s set s.clientRequestId = :key where s.id = :id")
	void rebindRequest(Long id, UUID key);
}

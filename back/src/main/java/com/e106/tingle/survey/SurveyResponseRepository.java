package com.e106.tingle.survey;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {

	Optional<SurveyResponse> findByIdAndUserId(Long id, Long userId);

	// 답 저장·삭제·완료를 회차 단위로 줄 세운다(완료와 동시에 들어온 저장이 완료 뒤에 끼어들지 않게).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from SurveyResponse s where s.id = :id and s.userId = :userId")
	Optional<SurveyResponse> findOwnedForUpdate(Long id, Long userId);

	// 성향 분석 결과는 회원의 가장 최근 완료 회차 것만 적용한다(늦게 끝난 이전 회차 분석이 덮어쓰지 않게).
	boolean existsByUserIdAndCompletedAtAfter(Long userId, Instant completedAt);

	Optional<SurveyResponse> findByUserIdAndVersionCodeAndCompletedAtIsNull(Long userId, String versionCode);
}

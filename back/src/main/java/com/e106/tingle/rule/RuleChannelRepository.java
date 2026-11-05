package com.e106.tingle.rule;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface RuleChannelRepository extends JpaRepository<RuleChannel, String> {

	// 처음 전환할 때 빈 행을 만든다. 동시에 불러도 하나만 생긴다.
	@Modifying
	@Query(value = "insert into rule_channel(name, release_id, survey_version, updated_at) "
		+ "values (:name, null, null, :now) on conflict (name) do nothing", nativeQuery = true)
	void ensure(String name, Instant now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from RuleChannel c where c.name = :name")
	Optional<RuleChannel> findByNameForUpdate(String name);

	// 서버 하나가 아니라 여러 대(블루·그린)에서도 맞도록 캐시하지 않고 매번 읽는다(기본 키 한 건).
	@Query("select c.surveyVersion from RuleChannel c where c.name = 'current'")
	Optional<String> currentSurveyVersion();
}

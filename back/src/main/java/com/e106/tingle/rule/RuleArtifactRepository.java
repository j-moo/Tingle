package com.e106.tingle.rule;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface RuleArtifactRepository extends JpaRepository<RuleArtifact, UUID> {

	boolean existsByKindAndVersion(RuleKind kind, String version);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from RuleArtifact a where a.id = :id")
	Optional<RuleArtifact> findByIdForUpdate(UUID id);

	// 게시할 때 조합의 정의를 id 순서로 잠근다(같은 정의를 쓰는 다른 작업과 순서를 맞춰 교착을 피한다).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from RuleArtifact a where a.id in :ids order by a.id")
	List<RuleArtifact> findAllForUpdate(Collection<UUID> ids);

	@Query("select a from RuleArtifact a where a.kind = :kind and a.version = :version and a.publishedAt is not null")
	Optional<RuleArtifact> findPublished(RuleKind kind, String version);

	@Query(value = "select * from rule_artifact where kind = 'MIGRATION' and published_at is not null "
		+ "and document ->> 'fromSurveyVersion' = :from and document ->> 'toSurveyVersion' = :to", nativeQuery = true)
	Optional<RuleArtifact> findPublishedMigration(String from, String to);
}

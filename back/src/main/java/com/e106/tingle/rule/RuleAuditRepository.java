package com.e106.tingle.rule;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RuleAuditRepository extends JpaRepository<RuleAudit, Long> {

	// 정의를 만들거나 고친 적이 있는 사람(작성자). 본인 승인 금지 판단에 쓴다.
	@Query("select distinct a.actorId from RuleAudit a where a.targetId in :artifactIds "
		+ "and a.action in (com.e106.tingle.rule.RuleAudit.Action.ARTIFACT_CREATE, "
		+ "com.e106.tingle.rule.RuleAudit.Action.ARTIFACT_UPDATE)")
	Set<Long> findArtifactAuthors(Collection<UUID> artifactIds);
}

package com.e106.tingle.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ProposalRepository extends JpaRepository<MatchProposal, Long> {
	String ELIGIBLE = "(select count(*) from users u where u.id in (p.user_low_id,p.user_high_id) "
		+ "and u.status='ACTIVE')=2 and not exists (select 1 from matching_participant b "
		+ "where b.matching_id=p.id and b.blocked_at is not null) ";
	String VISIBLE = "select p.* from matching p join matching_participant own on own.matching_id=p.id "
		+ "and own.user_id=:userId where (p.user_low_id=:userId or p.user_high_id=:userId) "
		+ "and p.proposed_at is not null and p.status not in ('WAITING','NOT_MATCHED','ACCEPTED','CLOSED') "
		+ "and p.proposed_at+interval '24 hours'>:now and " + ELIGIBLE
		+ "and (own.response is null or p.status in ('PENDING','ONE_ACCEPTED')) ";

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from MatchProposal p where p.id = :id and p.userLow is not null and p.userHigh is not null")
	Optional<MatchProposal> lockById(@Param("id") long id);

	@Query(value = VISIBLE + "order by p.proposed_at desc,p.id desc", nativeQuery = true)
	List<MatchProposal> findFirstPage(@Param("userId") long userId, @Param("now") Instant now, Pageable page);

	@Query(value = VISIBLE + "and (p.proposed_at<:at or (p.proposed_at=:at and p.id<:id)) "
		+ "order by p.proposed_at desc,p.id desc", nativeQuery = true)
	List<MatchProposal> findNextPage(@Param("userId") long userId, @Param("now") Instant now, @Param("at") Instant at,
		@Param("id") long id, Pageable page);

	@Query(value = "select exists(select 1 from matching p where p.id=:id and " + ELIGIBLE + ")", nativeQuery = true)
	boolean eligible(long id);
}

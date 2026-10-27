package com.e106.tingle.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ProposalRepository extends JpaRepository<MatchProposal, Long> {
	String VISIBLE = "select p.* from match_proposals p join user_pairs r on r.pair_id=p.pair_id "
		+ "where (r.user_low=:userId or r.user_high=:userId) "
		+ "and not exists (select 1 from app_users u where u.user_id in (r.user_low,r.user_high) "
		+ "and u.status<>'ACTIVE') and not exists (select 1 from user_blocks b "
		+ "where (b.blocker_id=r.user_low and b.blocked_id=r.user_high) "
		+ "or (b.blocker_id=r.user_high and b.blocked_id=r.user_low)) "
		+ "and (r.declined_until is null or r.declined_until<=:now or r.declined_proposal_id=p.proposal_id) "
		+ "and p.status<>'ACCEPTED' and (p.expires_at>:now and ("
		+ "(r.user_low=:userId and p.low_decision is null) "
		+ "or (r.user_high=:userId and p.high_decision is null) "
		+ "or p.status in ('PENDING','WAITING_FOR_OTHER'))) ";

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from MatchProposal p where p.id = :id")
	Optional<MatchProposal> lockById(@Param("id") long id);

	@Query(value = VISIBLE + "order by p.created_at desc, p.proposal_id desc", nativeQuery = true)
	List<MatchProposal> findFirstPage(@Param("userId") long userId, @Param("now") Instant now, Pageable page);

	@Query(value = VISIBLE + "and (p.created_at<:at or (p.created_at=:at and p.proposal_id<:id)) "
		+ "order by p.created_at desc, p.proposal_id desc", nativeQuery = true)
	List<MatchProposal> findNextPage(@Param("userId") long userId, @Param("now") Instant now, @Param("at") Instant at,
		@Param("id") long id, Pageable page);

	@Query("select p from MatchProposal p join PairRelation r on r.id = p.pairId "
		+ "where (r.userLow = :userId and (p.lowRequestKey = :key or p.lowCancelRequestKey = :key)) "
		+ "or (r.userHigh = :userId and (p.highRequestKey = :key or p.highCancelRequestKey = :key))")
	Optional<MatchProposal> findRequest(@Param("userId") long userId, @Param("key") UUID key);
}

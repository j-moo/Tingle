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
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from MatchProposal p where p.id = :id")
	Optional<MatchProposal> lockById(@Param("id") long id);

	@Query("select p from MatchProposal p join PairRelation r on r.id = p.pairId "
		+ "where r.userLow = :userId or r.userHigh = :userId order by p.createdAt desc, p.id desc")
	List<MatchProposal> findFirstPage(@Param("userId") long userId, Pageable page);

	@Query("select p from MatchProposal p join PairRelation r on r.id = p.pairId "
		+ "where (r.userLow = :userId or r.userHigh = :userId) "
		+ "and (p.createdAt < :at or (p.createdAt = :at and p.id < :id)) order by p.createdAt desc, p.id desc")
	List<MatchProposal> findNextPage(@Param("userId") long userId, @Param("at") Instant at,
		@Param("id") long id, Pageable page);

	@Query("select p from MatchProposal p join PairRelation r on r.id = p.pairId "
		+ "where (r.userLow = :userId and p.lowRequestKey = :key) "
		+ "or (r.userHigh = :userId and p.highRequestKey = :key)")
	Optional<MatchProposal> findRequest(@Param("userId") long userId, @Param("key") UUID key);
}

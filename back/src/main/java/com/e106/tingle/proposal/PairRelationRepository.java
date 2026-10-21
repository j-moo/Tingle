package com.e106.tingle.proposal;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PairRelationRepository extends JpaRepository<PairRelation, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from PairRelation p where p.id = :id")
	Optional<PairRelation> lockById(@Param("id") long id);
}

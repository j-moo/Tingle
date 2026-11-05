package com.e106.tingle.rule;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface RuleReleaseRepository extends JpaRepository<RuleRelease, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from RuleRelease r where r.id = :id")
	Optional<RuleRelease> findByIdForUpdate(UUID id);
}

package com.e106.tingle.profile;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PublicProfileRepository extends JpaRepository<PublicProfile, Long> {
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select p from PublicProfile p where p.userId = :id")
	Optional<PublicProfile> lockById(@Param("id") long id);
}

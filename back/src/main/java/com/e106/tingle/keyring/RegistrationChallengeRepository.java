package com.e106.tingle.keyring;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RegistrationChallengeRepository extends JpaRepository<RegistrationChallenge, Long> {

	Optional<RegistrationChallenge> findByUserIdAndRequestKey(long userId, UUID requestKey);

	Optional<RegistrationChallenge> findByUserIdAndRegistrationRequestKey(long userId, UUID registrationRequestKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from RegistrationChallenge c where c.userId=:userId and c.challengeId=:challengeId")
	Optional<RegistrationChallenge> findOwnedForUpdate(@Param("userId") long userId,
		@Param("challengeId") UUID challengeId);
}

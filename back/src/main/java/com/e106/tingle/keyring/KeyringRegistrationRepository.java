package com.e106.tingle.keyring;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface KeyringRegistrationRepository extends JpaRepository<Keyring, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select k from Keyring k where k.id=:id")
	Optional<Keyring> findByIdForUpdate(@Param("id") long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select k from Keyring k where k.serialNumber=:serial")
	Optional<Keyring> findBySerialForUpdate(@Param("serial") String serial);
}

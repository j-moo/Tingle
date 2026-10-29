package com.e106.tingle.settings.snapshot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsSnapshotRepository extends JpaRepository<SettingsSnapshot, Long> {

	Optional<SettingsSnapshot> findByUserIdAndSettingsVersion(long userId, long settingsVersion);
}

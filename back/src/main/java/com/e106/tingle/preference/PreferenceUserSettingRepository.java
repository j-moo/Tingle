package com.e106.tingle.preference;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.e106.tingle.settings.UserSetting;

import jakarta.persistence.LockModeType;

public interface PreferenceUserSettingRepository extends JpaRepository<UserSetting, Long> {

	@Modifying
	@Query(value = """
		INSERT INTO user_setting (user_id, vibration_enabled, led_enabled, settings_version)
		VALUES (:userId, true, true, 0)
		ON CONFLICT (user_id) DO NOTHING
		""", nativeQuery = true)
	int insertInitialIfAbsent(long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from UserSetting s where s.userId = :userId")
	Optional<UserSetting> findForUpdate(long userId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select s from UserSetting s where s.userId = :userId")
	Optional<UserSetting> findForRead(long userId);
}

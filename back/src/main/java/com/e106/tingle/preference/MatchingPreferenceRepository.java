package com.e106.tingle.preference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MatchingPreferenceRepository extends JpaRepository<MatchingPreference, Long> {

	@Modifying
	@Query(value = """
		INSERT INTO matching_preference
			(user_id, matching_enabled, age_preference_mode, min_age, max_age, photo_required)
		VALUES (:userId, true, NULL, NULL, NULL, false)
		ON CONFLICT (user_id) DO NOTHING
		""", nativeQuery = true)
	int insertInitialIfAbsent(long userId);
}

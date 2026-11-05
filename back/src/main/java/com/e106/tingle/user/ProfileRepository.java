package com.e106.tingle.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

	// canonical의 lower(nickname) UNIQUE 인덱스와 같은 DB 대소문자 비교를 쓴다.
	@Query("select p from Profile p where lower(p.nickname) = lower(:nickname)")
	Optional<Profile> findByNicknameIgnoreCase(String nickname);
}

package com.e106.tingle.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

	// nicknameKey 는 ProfileRules.nicknameKey(닉네임) 으로 만든다.
	Optional<Profile> findByNicknameKey(String nicknameKey);
}

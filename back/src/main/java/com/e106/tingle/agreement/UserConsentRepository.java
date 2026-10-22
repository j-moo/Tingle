package com.e106.tingle.agreement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

	// 기록 순서대로. 항목별 마지막 행이 현재 상태다.
	List<UserConsent> findByUserIdOrderByIdAsc(Long userId);
}

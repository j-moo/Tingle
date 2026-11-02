package com.e106.tingle.agreement;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

	// 기록 순서대로. 항목별 마지막 행이 현재 상태다.
	List<UserConsent> findByUserIdOrderByIdAsc(Long userId);

	Optional<UserConsent> findFirstByUserIdAndConsentTypeOrderByIdDesc(Long userId, String consentType);

	/** 이 항목의 가장 최근 기록이 동의인지. 선택 정보 저장 전 검사에 쓴다. */
	default boolean isAgreed(long userId, String consentType) {
		return findFirstByUserIdAndConsentTypeOrderByIdDesc(userId, consentType).map(UserConsent::isAgreed)
			.orElse(false);
	}
}

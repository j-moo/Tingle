package com.e106.tingle.withdrawal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
// 개발·운영은 늘 켠다. 통합 테스트만 끈다(application-test.yml).
@ConditionalOnProperty(name = "app.withdrawal.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WithdrawalIdentityScheduler {
	private final WithdrawalIdentityService identities;

	@Scheduled(fixedDelayString = "${app.withdrawal.retention-poll-ms:60000}")
	public void purge() {
		identities.purgeExpired();
	}
}

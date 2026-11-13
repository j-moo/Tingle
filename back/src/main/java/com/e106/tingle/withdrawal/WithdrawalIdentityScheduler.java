package com.e106.tingle.withdrawal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "app.withdrawal.scheduling-enabled", havingValue = "true")
@RequiredArgsConstructor
public class WithdrawalIdentityScheduler {
	private final WithdrawalIdentityService identities;

	@Scheduled(fixedDelayString = "${app.withdrawal.retention-poll-ms:60000}")
	public void purge() {
		identities.purgeExpired();
	}
}

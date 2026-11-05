package com.e106.tingle.chat.deletion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chat-deletion.scheduling-enabled", havingValue = "true")
public class ChatDeletionScheduler {
	private final ChatDeletionService service;

	@Scheduled(fixedDelayString = "${app.chat-deletion.poll-ms:60000}")
	public void deleteDueChat() {
		service.claimNext().ifPresent(claim -> {
			try {
				service.deleteClaim(claim);
			} catch (RuntimeException exception) {
				service.failClaim(claim, "DB_DELETE_FAILED");
			}
		});
	}
}

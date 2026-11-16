package com.e106.tingle.chat.deletion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
// 개발·운영은 늘 켠다. 통합 테스트만 끈다(application-test.yml).
@ConditionalOnProperty(name = "app.chat-deletion.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ChatDeletionScheduler {
	private final ChatDeletionService service;

	@Scheduled(fixedDelayString = "${app.chat-deletion.poll-ms:60000}")
	public void deleteDueChat() {
		service.claimNext().ifPresent(claim -> {
			try {
				service.deleteClaim(claim);
			} catch (RuntimeException exception) {
				// 저널 불가 등으로 삭제가 계속 보류되는 것을 알아챌 수 있게 예외 종류만 남긴다(채팅 내용·경로는 남기지 않는다).
				log.warn("chat deletion failed: {}", exception.getClass().getSimpleName());
				service.failClaim(claim, "DB_DELETE_FAILED");
			}
		});
	}
}

package com.e106.tingle.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

/**
 * 개인정보 동의 전에 로그인한 회원의 소셜 확인 이메일을 DB가 아닌 메모리에만 잠깐 들고 있다가,
 * 동의가 들어오면 꺼내 저장하게 한다(동의 전에는 DB에 남기지 않는다).
 * 시간이 지났거나 서버가 재시작됐거나 다른 서버로 요청이 가면 없으며, 그때는 다음 로그인에서 저장된다.
 */
@Component
@RequiredArgsConstructor
public class PendingEmails {

	static final Duration TTL = Duration.ofMinutes(30);

	private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
	private final Clock clock;

	/** sessionId 는 이메일을 받은 로그인 세션이다. 꺼낼 때 그 세션이 가장 최근 로그인인지 확인한다. */
	public void hold(long userId, String email, UUID sessionId) {
		if (email == null) {
			entries.remove(userId);
		} else {
			entries.put(userId, new Entry(email, sessionId, clock.instant().plus(TTL)));
		}
	}

	/**
	 * 들고 있던 이메일(만료됐으면 null). 트랜잭션 안이면 커밋된 뒤에만 지워서, 롤백되면 같은 동의 재시도에서 다시 쓸 수 있다.
	 */
	public String take(long userId, Predicate<UUID> latestSession) {
		Entry entry = entries.get(userId);
		if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
			return null;
		}
		if (!latestSession.test(entry.sessionId())) {
			entries.remove(userId, entry);
			return null;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					entries.remove(userId, entry);
				}
			});
		} else {
			entries.remove(userId, entry);
		}
		return entry.email();
	}

	/** 탈퇴 트랜잭션이 커밋되면 이 회원이 보관 중인 이메일을 지운다(롤백되면 그대로 둔다). */
	public void forgetAfterCommit(long userId) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				entries.remove(userId);
			}
		});
	}

	/** 동의하지 않은 회원의 이메일이 30분을 넘겨 남지 않게 만료분을 지운다(아래 Purge 가 1분마다 부른다). */
	void purgeExpired() {
		Instant now = clock.instant();
		entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
	}

	// 테스트는 시계를 대체하므로 백그라운드에서 시계를 부르지 않게 끈다(app.pending-emails.scheduling-enabled=false).
	@Component
	@RequiredArgsConstructor
	@ConditionalOnProperty(name = "app.pending-emails.scheduling-enabled", havingValue = "true", matchIfMissing = true)
	static class Purge {

		private final PendingEmails pendingEmails;

		@Scheduled(fixedDelay = 60_000)
		void run() {
			pendingEmails.purgeExpired();
		}
	}

	int size() {
		return entries.size();
	}

	private record Entry(String email, UUID sessionId, Instant expiresAt) {
	}
}

package com.e106.tingle.proposal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.e106.tingle.global.i18n.Messages;
import com.e106.tingle.push.PushDeliveryLookup;
import com.e106.tingle.push.PushDeliveryTarget;
import com.e106.tingle.push.PushGateway;
import com.e106.tingle.push.PushNotificationKind;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 새 제안이 커밋되면 두 사람에게 NEW_PROPOSAL 푸시를 한 번 보낸다(data 전용). 앱은 title · body가 있으면 그 문구를 쓴다.
 * 제안 화면을 열기 전이라 상대 닉네임은 넣지 않는다.
 * 제안 목록이 원본이라 푸시가 빠져도 제안은 그대로 보인다.
 */
// ponytail: 커밋 뒤 1회 전송, 재시도 큐 없음. FCM 일시 오류로 빠지는 알림이 문제가 되면 채팅 푸시처럼 작업 테이블로 옮긴다
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalPushNotifier {

	// 제안은 proposed_at 부터 24시간만 목록에 보인다(ProposalRepository.VISIBLE). 그 뒤 도착한 알림은 의미가 없다
	private static final Duration PUSH_TTL = Duration.ofHours(24);

	private final PushDeliveryLookup deliveries;
	private final PushGateway gateway;
	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

	/** 현재 트랜잭션이 커밋된 뒤에만 보낸다. 롤백되면 보내지 않는다. */
	public void afterCommit(long proposalId, long lowUserId, long highUserId, Instant proposedAt) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					worker.execute(() -> {
						send(proposalId, lowUserId, proposedAt);
						send(proposalId, highUserId, proposedAt);
					});
				} catch (RejectedExecutionException exception) {
					// 서버 종료 중. 제안은 이미 커밋됐고 목록에서 보인다
					log.warn("event=proposal_push_skipped reason=shutdown");
				}
			}
		});
	}

	/** 한 사람 몫. 실패해도 다른 사람 몫과 제안에는 영향이 없다. */
	void send(long proposalId, long userId, Instant proposedAt) {
		try {
			if (!gateway.ready()) {
				return;
			}
			Instant deadline = proposedAt.plus(PUSH_TTL);
			// 같은 제안 알림은 기기에서 하나로 합치고, 다른 제안 알림은 따로 남긴다(앱이 noticeId 로 알림을 구분한다)
			String noticeId = "proposal-" + proposalId;
			List<PushDeliveryLookup.Candidate> candidates = deliveries.candidates(userId,
				PushNotificationKind.NEW_PROPOSAL);
			for (PushDeliveryLookup.Candidate candidate : candidates) {
				try {
					sendTo(candidate, noticeId, proposalId, userId, deadline);
				} catch (RuntimeException exception) {
					warn(exception);   // 한 기기 실패가 같은 사람의 다른 기기를 막지 않는다
				}
			}
		} catch (RuntimeException exception) {
			warn(exception);
		}
	}

	private void sendTo(PushDeliveryLookup.Candidate candidate, String noticeId, long proposalId, long userId,
		Instant deadline) {
		PushDeliveryTarget target = deliveries.resolve(candidate).orElse(null);
		if (target == null) {
			return;
		}
		PushGateway.Result result = gateway.send(target.token(), noticeId, Map.of(
			"type", PushNotificationKind.NEW_PROPOSAL.name(),
			"title", Messages.get("push.proposal.title"),
			"body", Messages.get("push.proposal.body"),
			"noticeId", noticeId,
			"proposalId", Long.toString(proposalId),
			"recipientUserId", Long.toString(userId),
			"bindingId", candidate.bindingId().toString(),
			"installationId", candidate.installationId().toString(),
			"expiresAt", deadline.toString()), deadline);
		if (result == PushGateway.Result.INVALID_TOKEN) {
			deliveries.invalidate(candidate);
		}
	}

	// 알림 실패가 제안을 되돌리지 않는다. 토큰·사용자 정보는 남기지 않는다
	private static void warn(RuntimeException exception) {
		log.warn("event=proposal_push_failed failure={}", exception.getClass().getSimpleName());
	}

	// 채팅 푸시 스케줄러처럼 멈춘 FCM 호출을 기다리지 않는다(남은 알림은 버린다)
	@PreDestroy
	void close() {
		worker.shutdownNow();
	}
}

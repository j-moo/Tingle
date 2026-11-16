package com.e106.tingle.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.push.PushDeliveryLookup;
import com.e106.tingle.push.PushDeliveryTarget;
import com.e106.tingle.push.PushGateway;
import com.e106.tingle.push.PushNotificationKind;

class ProposalPushNotifierTest {

	private static final Instant PROPOSED = Instant.parse("2026-11-11T06:21:09Z");
	private final PushDeliveryLookup deliveries = mock(PushDeliveryLookup.class);
	private final PushGateway gateway = mock(PushGateway.class);
	private final ChatService chats = mock(ChatService.class);
	private final ChatRoom room = mock(ChatRoom.class);
	private final ProposalPushNotifier notifier = new ProposalPushNotifier(deliveries, gateway, chats);

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("제안 알림은 앱 공통 필드를 담고 제안이 목록에서 사라지는 24시간 뒤까지만 유효하다")
	void sendsNewProposalData() {
		PushDeliveryLookup.Candidate candidate = reachable(89, "fcm-token", PushGateway.Result.ACCEPTED);

		notifier.send(9, 89, PROPOSED);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
		Instant deadline = PROPOSED.plusSeconds(86400);
		verify(gateway).send(eq("fcm-token"), eq("proposal-9"), data.capture(), eq(deadline));
		assertThat(data.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of("type", "NEW_PROPOSAL",
			"noticeId", "proposal-9", "proposalId", "9", "recipientUserId", "89",
			"bindingId", candidate.bindingId().toString(), "installationId", candidate.installationId().toString(),
			"expiresAt", deadline.toString(), "title", "새 매칭 제안", "body", "가까이에서 만난 사람과 매칭 제안이 도착했어요."));
		verify(deliveries, never()).invalidate(any());
	}

	@Test
	@DisplayName("매칭 성사 알림은 채팅방 번호와 상대 닉네임 문구를 싣는다")
	void matchedPushCarriesRoomAndPartnerName() {
		PushDeliveryLookup.Candidate candidate = reachable(89, "fcm-token", PushGateway.Result.ACCEPTED);
		Instant deadline = PROPOSED.plusSeconds(7 * 86400);
		given(chats.linkedForMatching(9)).willReturn(Optional.of(room));
		given(chats.available(room, 89)).willReturn(true);
		notifier.sendMatched(9, 89, 31, deadline, "초록");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
		verify(gateway).send(eq("fcm-token"), eq("match-9"), data.capture(), eq(deadline));
		assertThat(data.getValue()).containsEntry("type", "CHAT_ROOM_CREATED").containsEntry("chatRoomId", "31")
			.containsEntry("recipientUserId", "89").containsEntry("title", "우연한 만남이 있었어요!")
			.containsEntry("body", "초록님과 대화를 시작할 수 있어요.");
	}

	@Test
	@DisplayName("받는 사람이 그 방에서 대화할 수 없으면(AI 동의 철회 등) 매칭 성사 알림을 보내지 않는다")
	void matchedPushIsSkippedWhenRecipientCannotChat() {
		reachable(89, "fcm-token", PushGateway.Result.ACCEPTED);
		given(chats.linkedForMatching(9)).willReturn(Optional.of(room));
		given(chats.available(room, 89)).willReturn(false);

		notifier.sendMatched(9, 89, 31, PROPOSED.plusSeconds(7 * 86400), "초록");

		verify(gateway, never()).send(anyString(), anyString(), anyMap(), any());
	}

	@Test
	@DisplayName("지워진 토큰이면 그 기기 연결을 지운다")
	void dropsUnregisteredToken() {
		PushDeliveryLookup.Candidate candidate = reachable(98, "old-token", PushGateway.Result.INVALID_TOKEN);

		notifier.send(9, 98, PROPOSED);

		verify(deliveries).invalidate(candidate);
	}

	@Test
	@DisplayName("푸시가 꺼진 환경이면 보내지 않는다")
	void skipsWhenPushIsOff() {
		given(gateway.ready()).willReturn(false);

		notifier.send(9, 98, PROPOSED);

		verify(deliveries, never()).candidates(anyLong(), any());
		verify(gateway, never()).send(anyString(), anyString(), anyMap(), any());
	}

	@Test
	@DisplayName("한 사람의 조회가 실패해도 다른 사람에게는 보낸다")
	void oneFailureDoesNotStopTheOther() {
		given(gateway.ready()).willReturn(true);
		given(deliveries.candidates(89, PushNotificationKind.NEW_PROPOSAL)).willThrow(new IllegalStateException());
		reachable(98, "fcm-token", PushGateway.Result.ACCEPTED);
		TransactionSynchronizationManager.initSynchronization();

		notifier.afterCommit(9, 89, 98, PROPOSED);
		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

		verify(gateway, timeout(2000)).send(eq("fcm-token"), eq("proposal-9"), anyMap(), any());
	}

	@Test
	@DisplayName("커밋되지 않으면(롤백) 보내지 않는다")
	void nothingWithoutCommit() {
		given(gateway.ready()).willReturn(true);
		TransactionSynchronizationManager.initSynchronization();

		notifier.afterCommit(9, 89, 98, PROPOSED);
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		// 발송은 다른 스레드라 잠깐 기다린 뒤 확인한다
		verify(deliveries, after(300).never()).candidates(anyLong(), any());
	}

	@Test
	@DisplayName("한 기기가 실패해도 같은 사람의 다른 기기에는 보낸다")
	void oneDeviceFailureDoesNotStopOtherDevices() {
		PushDeliveryLookup.Candidate broken = new PushDeliveryLookup.Candidate(1, 89, UUID.randomUUID(),
			PushNotificationKind.NEW_PROPOSAL, "fp", UUID.randomUUID(), PROPOSED.minusSeconds(60));
		PushDeliveryLookup.Candidate working = reachable(89, "fcm-token", PushGateway.Result.ACCEPTED);
		given(deliveries.candidates(89, PushNotificationKind.NEW_PROPOSAL)).willReturn(List.of(broken, working));
		given(deliveries.resolve(broken)).willThrow(new IllegalStateException());

		notifier.send(9, 89, PROPOSED);

		verify(gateway).send(eq("fcm-token"), eq("proposal-9"), anyMap(), any());
	}

	private PushDeliveryLookup.Candidate reachable(long userId, String token, PushGateway.Result result) {
		PushDeliveryLookup.Candidate candidate = new PushDeliveryLookup.Candidate(userId, userId, UUID.randomUUID(),
			PushNotificationKind.NEW_PROPOSAL, "fp", UUID.randomUUID(), PROPOSED.minusSeconds(60));
		PushDeliveryTarget target = mock(PushDeliveryTarget.class);
		given(target.token()).willReturn(token);
		given(gateway.ready()).willReturn(true);
		given(deliveries.candidates(userId, PushNotificationKind.NEW_PROPOSAL)).willReturn(List.of(candidate));
		given(deliveries.candidates(userId, PushNotificationKind.CHAT_ROOM_CREATED)).willReturn(List.of(candidate));
		given(deliveries.resolve(candidate)).willReturn(Optional.of(target));
		given(gateway.send(eq(token), anyString(), anyMap(), any())).willReturn(result);
		return candidate;
	}
}

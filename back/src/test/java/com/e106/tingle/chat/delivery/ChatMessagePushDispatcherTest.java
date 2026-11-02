package com.e106.tingle.chat.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.Test;

import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;
import com.e106.tingle.push.PushGateway;

class ChatMessagePushDispatcherTest {
	@Test
	void restoreGatePreventsClaimingAndProviderCalls() {
		ChatMessagePushStore store = mock(ChatMessagePushStore.class);
		PushGateway gateway = mock(PushGateway.class);
		RestoreServingGateFilter servingGate = mock(RestoreServingGateFilter.class);
		ChatMessagePushDispatcher dispatcher = new ChatMessagePushDispatcher(store, gateway, servingGate);
		assertThat(dispatcher.runDue()).isZero();
		then(store).shouldHaveNoInteractions();
		then(gateway).shouldHaveNoInteractions();
	}
}

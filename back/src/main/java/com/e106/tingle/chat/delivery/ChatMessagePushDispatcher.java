package com.e106.tingle.chat.delivery;

import org.springframework.stereotype.Service;

import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;
import com.e106.tingle.push.PushDeliveryTarget;
import com.e106.tingle.push.PushGateway;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatMessagePushDispatcher {
	private final ChatMessagePushStore store;
	private final PushGateway gateway;
	private final RestoreServingGateFilter servingGate;

	public int runDue() {
		if (!servingGate.servingAllowed() || !gateway.ready()) {
			return 0;
		}
		int processed = 0;
		while (processed < 20 && servingGate.servingAllowed()) {
			ChatMessagePushStore.Claim claim = store.claim();
			if (claim == null) {
				break;
			}
			for (ChatMessagePushStore.Target target : store.targets(claim)) {
				PushGateway.Result result;
				try {
					PushDeliveryTarget resolved = store.resolve(claim, target).orElse(null);
					if (resolved == null || !servingGate.servingAllowed()) {
						continue;
					}
					result = gateway.send(resolved.token(), target.eventId(), target.data(), target.deadline());
				} catch (RuntimeException exception) {
					result = PushGateway.Result.RETRY;
				}
				store.record(claim, target, result);
			}
			store.finish(claim);
			processed++;
		}
		return processed;
	}
}

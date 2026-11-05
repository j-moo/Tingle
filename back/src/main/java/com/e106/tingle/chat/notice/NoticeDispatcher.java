package com.e106.tingle.chat.notice;

import org.springframework.stereotype.Service;

import com.e106.tingle.push.PushDeliveryTarget;
import com.e106.tingle.push.PushGateway;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NoticeDispatcher {
	private final NoticeDeliveryStore store;
	private final PushGateway gateway;
	private final EndNoticePolicy endNotices;

	public int runDue() {
		if (!endNotices.enabled() || !gateway.ready()) {
			return 0;
		}
		int processed = 0;
		while (processed < 20) {
			NoticeDeliveryStore.Claim claim = store.claim();
			if (claim == null) {
				break;
			}
			boolean retry = false;
			for (NoticeDeliveryStore.Target target : store.targets(claim)) {
				PushGateway.Result result;
				try {
					PushDeliveryTarget resolved = store.resolve(claim, target).orElse(null);
					if (resolved == null) {
						continue;
					}
					result = gateway.send(resolved.token(), target.noticeId(),
						target.data(), target.deadline());
				} catch (RuntimeException exception) {
					result = PushGateway.Result.RETRY;
				}
				retry |= store.record(claim, target, result);
			}
			store.finish(claim, retry);
			processed++;
		}
		return processed;
	}
}

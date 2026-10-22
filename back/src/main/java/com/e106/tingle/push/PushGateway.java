package com.e106.tingle.push;

import java.time.Instant;
import java.util.Map;

public interface PushGateway {
	boolean ready();

	Result send(String token, String noticeId, Map<String, String> data, Instant deadline);

	enum Result { ACCEPTED, RETRY, INVALID_TOKEN, REJECTED }
}

package com.e106.tingle.push;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** local·test 전용. FCM 키 없이 뜨도록 보내지 않는 게이트웨이를 쓴다(ready()=false 라 발송 작업은 대기한다). */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
public class LocalPushConfiguration {
	@Bean
	PushGateway localPushGateway() {
		return new PushGateway() {
			@Override
			public boolean ready() {
				return false;
			}

			@Override
			public Result send(String token, String noticeId, java.util.Map<String, String> data,
				java.time.Instant deadline) {
				return Result.RETRY;
			}
		};
	}
}

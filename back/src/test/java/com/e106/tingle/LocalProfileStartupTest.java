package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.keyring.DevSettingsIssuer;
import com.e106.tingle.push.PushGateway;

/**
 * 환경변수 없이 local 프로필만으로 서버가 뜨는지(./gradlew bootRun 과 같은 설정) 확인한다.
 * DB 만 compose 대신 Testcontainers 를 쓴다. 서명 키·탈퇴 키 등은 application-local.yml 의 시험용 값을 쓴다.
 */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LocalProfileStartupTest {

	@Autowired
	private Environment environment;
	@Autowired
	private DevSettingsIssuer settings;
	@Autowired
	private PushGateway push;

	@Test
	void startsWithRepositoryTestKeyAndLocalPushGateway() {
		assertThat(environment.getActiveProfiles()).containsExactly("local");
		assertThat(settings.publicKeyRawHex())
			.isEqualTo("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8");
		assertThat(push.ready()).isFalse();
	}
}

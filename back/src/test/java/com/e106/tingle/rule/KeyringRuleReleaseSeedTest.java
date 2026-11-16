package com.e106.tingle.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.encounter.peer.Ter2BusinessReleaseResolver;

import tools.jackson.databind.json.JsonMapper;

// 다른 테스트가 같은 버전 이름의 규칙을 만드는 DB와 섞이지 않게 이 클래스만의 컨텍스트(빈 DB)를 쓴다.
@SpringBootTest(properties = "spring.application.name=keyring-rule-release-seed-test")
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class KeyringRuleReleaseSeedTest {

	@Autowired
	private DataSource dataSource;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper jsonMapper;
	@Value("${app.keyring.peer.business-rule-release-id}")
	private String releaseId;
	@Value("${app.keyring.peer.business-release-hash}")
	private String releaseHash;

	@Test
	void bundledReleaseMatchesTheConfiguredIdAndHashAndCanBeAppliedTwice() {
		KeyringRuleReleaseSeed.apply(dataSource);
		KeyringRuleReleaseSeed.apply(dataSource);

		assertThat(jdbc.queryForObject("select count(*) from rule_release", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from rule_artifact", Integer.class)).isEqualTo(2);
		Ter2BusinessReleaseResolver resolver = new Ter2BusinessReleaseResolver(jdbc, jsonMapper);
		ReflectionTestUtils.setField(resolver, "approved", true);
		ReflectionTestUtils.setField(resolver, "configuredRelease", releaseId);
		ReflectionTestUtils.setField(resolver, "configuredHash", releaseHash);
		assertThat(resolver.resolve("tingle-policy-v2", "r2-c-20261106", Instant.now())).hasToString(releaseId);
	}
}

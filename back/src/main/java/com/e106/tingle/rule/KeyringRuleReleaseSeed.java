package com.e106.tingle.rule;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 키링 매칭 규칙 릴리즈를 서버가 켜질 때 DB에 넣는다(이미 있으면 그대로 둔다).
 * DB마다 손으로 넣지 않아도 운영·개발·새 DB가 같은 릴리즈를 갖게 한다.
 * 테스트는 같은 버전 이름으로 규칙을 직접 만들어 보므로 넣지 않는다.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class KeyringRuleReleaseSeed implements ApplicationRunner {

	private final DataSource dataSource;

	@Override
	public void run(ApplicationArguments args) {
		apply(dataSource);
	}

	public static void apply(DataSource dataSource) {
		new ResourceDatabasePopulator(new ClassPathResource("database/keyring-rule-release.sql")).execute(dataSource);
	}
}

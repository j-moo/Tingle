package com.e106.tingle.support.lost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LostWithdrawalDdlTest {
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	@Test
	void additiveSqlAppliesTwiceAndPreservesExistingOwnershipWithoutBlockingDevices() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword()));
		jdbc.execute("create table keyring(id bigint primary key,owner_user_id bigint,state varchar(20))");
		jdbc.update("insert into keyring values(1,123,'REGISTERED')");
		String sql = Files.readString(Path.of("../infra/db/prod-changes/2026-11-06-lost-keyring-withdrawal.sql"));
		jdbc.execute(sql);
		jdbc.execute(sql);
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=1", Long.class)).isEqualTo(123);
		assertThat(jdbc.queryForObject("select permanently_blocked_at from keyring where id=1", Object.class))
			.isNull();
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
			+ "where table_schema='public' and table_name in "
			+ "('lost_reauthentication','lost_withdrawal_request','lost_case_command')", Integer.class))
			.isEqualTo(29);
		assertThat(jdbc.queryForObject("select count(*) from lost_withdrawal_request", Long.class)).isZero();
	}
}

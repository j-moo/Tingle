package com.e106.tingle.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.e106.tingle.ContractFixtures;
import com.e106.tingle.SharedPostgres;

class MemberWithdrawalDdlTest {
	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();

	@Test
	void appliesTwiceToExistingTablesWithoutDeletingRowsAndRejectsPartialTombstones() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword()));
		jdbc.execute("create table matching(id bigint primary key,user_low_id bigint not null,"
			+ "user_high_id bigint not null,status varchar(20) not null)");
		jdbc.execute("create table rule_audit(id bigint primary key,actor_id bigint not null)");
		jdbc.update("insert into matching values(1,10,20,'CLOSED')");
		jdbc.update("insert into rule_audit values(1,10)");
		String sql = ContractFixtures.memberWithdrawalSql();
		jdbc.execute(sql);
		jdbc.execute(sql);
		assertThat(jdbc.queryForObject("select count(*) from matching", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select actor_id from rule_audit where id=1", Long.class)).isEqualTo(10);
		assertThatThrownBy(() -> jdbc.update("update matching set user_low_id=null where id=1"))
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("update matching set user_low_id=null,user_high_id=null where id=1");
		jdbc.update("update rule_audit set actor_id=null where id=1");
		assertThatThrownBy(() -> jdbc.update("update matching set status='PROPOSED' where id=1"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}

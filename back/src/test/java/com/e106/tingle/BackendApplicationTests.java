package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackendApplicationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

	@Test
	@DisplayName("JPA가 생성하는 인증·프로필 문자열은 공식 DDL의 text이며 물리 FK를 만들지 않는다")
	void generatedStorageMatchesOfficialDesign() {
		List<String> types = jdbc.queryForList("select data_type from information_schema.columns "
			+ "where table_schema='public' and ((table_name='app_users' "
			+ "and column_name in ('provider','provider_subject','status','email')) "
			+ "or (table_name='profiles' and column_name in ('nickname','nickname_key','gender',"
			+ "'smoking_status','drinking_frequency','religion','introduction','image_url')) "
			+ "or (table_name='user_consents' and column_name in ('consent_type','document_version')))",
			String.class);
		assertThat(types).hasSize(14).containsOnly("text");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
			+ "where constraint_schema='public' and constraint_type='FOREIGN KEY'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns "
			+ "where table_schema='public' and table_name='app_users' and column_name='email'", String.class))
			.isEqualTo("YES");
	}

}

package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "erd"})
class BackendApplicationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

	@Test
	@DisplayName("새 25표 DDL의 공통 필드 타입과 물리 FK 미사용을 검증한다")
	void commonStorageMatchesCanonicalDesign() {
		List<String> types = jdbc.query("select table_name,column_name,data_type from information_schema.columns "
			+ "where table_schema='public' and ((table_name='users' "
			+ "and column_name in ('provider','provider_user_id','status','email')) "
			+ "or (table_name='profile' and column_name in ('nickname','gender','smoking_status',"
			+ "'drinking_frequency','religion','introduction','photo_url')) "
			+ "or (table_name='user_consents' and column_name in ('consent_type','document_version')))",
			(rs, row) -> rs.getString(1) + "." + rs.getString(2) + ":" + rs.getString(3));
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema='public' and table_type='BASE TABLE'", Integer.class)).isEqualTo(25);
		assertThat(types).containsExactlyInAnyOrder("users.provider:USER-DEFINED",
			"users.provider_user_id:character varying", "users.status:text", "users.email:character varying",
			"profile.nickname:character varying", "profile.gender:USER-DEFINED", "profile.smoking_status:USER-DEFINED",
			"profile.drinking_frequency:USER-DEFINED", "profile.religion:USER-DEFINED", "profile.introduction:text",
			"profile.photo_url:character varying", "user_consents.consent_type:text",
			"user_consents.document_version:text");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
			+ "where constraint_schema='public' and constraint_type='FOREIGN KEY'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns "
			+ "where table_schema='public' and table_name='users' and column_name='email'", String.class))
			.isEqualTo("YES");
	}

}

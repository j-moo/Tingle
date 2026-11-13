package com.e106.tingle;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 테스트 컨텍스트의 DB. 공용 PostgreSQL 컨테이너({@link SharedPostgres}) 안에 컨텍스트마다 빈 DB를 새로 만든다.
 * 컨텍스트마다 빈 DB를 받는 것은 전과 같고, 컨텍스트마다 컨테이너를 새로 띄우지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	JdbcConnectionDetails postgresConnection() {
		return SharedPostgres.newDatabase();
	}

}

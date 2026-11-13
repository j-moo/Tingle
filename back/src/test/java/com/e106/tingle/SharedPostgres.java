package com.e106.tingle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트 JVM 하나에 PostgreSQL 컨테이너를 하나만 띄우고, 요청마다 그 안에 빈 DB를 새로 만들어 준다.
 * 테스트 클래스(또는 스프링 컨텍스트)마다 빈 DB를 받는 것은 전과 같고, 컨테이너 기동(약 1.6초)과 메모리만 아낀다.
 * 컨테이너는 JVM이 끝날 때 Testcontainers(Ryuk)가 지운다.
 */
public final class SharedPostgres {

	// 컨텍스트 캐시에 남은 연결 풀 + 테스트가 직접 여는 연결을 받을 만큼 늘린다(기본 100).
	// 버리는 테스트 DB라 디스크 동기화(fsync)를 꺼서 쓰기를 빠르게 한다.
	private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17")
		.withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off");
	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private SharedPostgres() {
	}

	/** 새 빈 DB의 접속 정보. 같은 JVM 안에서 이름이 겹치지 않는다. */
	public static synchronized JdbcConnectionDetails newDatabase() {
		if (!CONTAINER.isRunning()) {
			CONTAINER.start();
		}
		String name = "test_" + SEQUENCE.incrementAndGet();
		try (Connection connection = DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(),
			CONTAINER.getPassword()); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + name);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not create test database " + name, exception);
		}
		String url = "jdbc:postgresql://" + CONTAINER.getHost() + ":"
			+ CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name + "?loggerLevel=OFF";
		String username = CONTAINER.getUsername();
		String password = CONTAINER.getPassword();
		return new JdbcConnectionDetails() {
			@Override
			public String getUsername() {
				return username;
			}

			@Override
			public String getPassword() {
				return password;
			}

			@Override
			public String getJdbcUrl() {
				return url;
			}
		};
	}
}

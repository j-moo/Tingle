package com.e106.tingle.global.jpa;

import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

/**
 * 컬럼 단위 CHECK 제약을 만들지 않는 PostgreSQL 방언.
 *
 * <p>Hibernate는 enum 컬럼에 허용 값 목록 검사(CHECK)를 자동으로 붙이는데, ddl-auto: update 는 이 검사를 고치지 않는다.
 * 그래서 선택지(성별·종교 등)를 추가하면 이미 만들어진 DB에는 새 값을 저장할 수 없게 된다.
 * 값 검증은 서버 코드(enum 변환)에서 하므로 DB 검사는 만들지 않는다. 설정: application.yml 의 hibernate.dialect
 */
public class NoColumnCheckPostgreSqlDialect extends PostgreSQLDialect {

	public NoColumnCheckPostgreSqlDialect() {
	}

	public NoColumnCheckPostgreSqlDialect(DialectResolutionInfo info) {
		super(info);
	}

	@Override
	public boolean supportsColumnCheck() {
		return false;
	}
}

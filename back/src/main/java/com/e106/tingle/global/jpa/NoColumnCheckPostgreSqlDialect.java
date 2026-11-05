package com.e106.tingle.global.jpa;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.service.ServiceRegistry;

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

	@Override
	public String getEnumTypeDeclaration(String name, String[] values) {
		return CanonicalEnumNames.sqlName(name);
	}

	@Override
	public String[] getCreateEnumTypeCommand(String name, String[] values) {
		String type = CanonicalEnumNames.sqlName(name);
		String labels = Arrays.stream(values).map(NoColumnCheckPostgreSqlDialect::literal)
			.collect(Collectors.joining(","));
		String sorted = Arrays.stream(values).sorted().map(NoColumnCheckPostgreSqlDialect::literal)
			.collect(Collectors.joining(","));
		String command = "do $tingle_enum$ declare existing text[]; begin "
			+ "perform pg_advisory_xact_lock(hashtext(current_schema() || '.tingle.enum." + type + "')); "
			+ "if to_regtype('" + type + "') is null then create type " + type + " as enum (" + labels + "); "
			+ "else select array_agg(enumlabel::text order by enumlabel::text collate \"C\") into existing "
			+ "from pg_enum where enumtypid=to_regtype('" + type + "'); "
			+ "if existing is distinct from array[" + sorted + "]::text[] then "
			+ "raise exception 'Existing enum " + type + " differs from mapped labels; manual review required'; "
			+ "end if; end if; end $tingle_enum$";
		return new String[] {command};
	}

	@Override
	public String[] getDropEnumTypeCommand(String name) {
		// Hibernate update drops auxiliary objects first. Never DROP TYPE CASCADE on stored enum columns.
		// create-drop removes its tables; canonical enum types deliberately remain reusable.
		return new String[0];
	}

	@Override
	public void contributeTypes(TypeContributions contributions, ServiceRegistry registry) {
		super.contributeTypes(contributions, registry);
		contributions.contributeJdbcType(new CanonicalPostgreSqlEnumJdbcType());
		contributions.getTypeConfiguration().getDdlTypeRegistry()
			.addDescriptor(new CanonicalPostgreSqlEnumDdlType(this));
	}

	private static String literal(String value) {
		return "'" + value.replace("'", "''") + "'";
	}
}

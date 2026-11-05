package com.e106.tingle.global.jpa;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.SessionFactoryObserver;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.mapping.BasicValue;
import org.hibernate.type.SqlTypes;

/** update가 auxiliary DDL 예외를 무시하므로, 생성 뒤 읽기 전용 검사로 시작을 차단한다. */
public class SchemaInvariantIntegrator implements Integrator {
	@Override
	public void integrate(Metadata metadata, BootstrapContext context, SessionFactoryImplementor factory) {
		if (!(factory.getJdbcServices().getDialect() instanceof PostgreSQLDialect)) {
			return;
		}
		Map<String, List<String>> enums = enumLabels(metadata);
		List<PostgreSqlSchemaContributor.VerifiedIndex> indexes = metadata.getDatabase()
			.getAuxiliaryDatabaseObjects().stream()
			.filter(PostgreSqlSchemaContributor.VerifiedIndex.class::isInstance)
			.map(PostgreSqlSchemaContributor.VerifiedIndex.class::cast).toList();
		// Integrators run after the builder's schema-export observer is registered.
		factory.addObserver(new SessionFactoryObserver() {
			@Override
			public void sessionFactoryCreated(SessionFactory created) {
				try (var session = created.openSession()) {
					session.doWork(connection -> {
						validateEnums(connection, enums);
						for (var index : indexes) {
							String query = index.validationSql(factory.getSqlStringGenerationContext());
							try (var statement = connection.createStatement();
								var rows = statement.executeQuery(query)) {
								if (!rows.next() || !rows.getBoolean(1)) {
									throw new HibernateException("Existing " + index.name()
										+ " is incompatible; manual review required");
								}
							}
						}
					});
				}
			}
		});
	}

	private Map<String, List<String>> enumLabels(Metadata metadata) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (var entity : metadata.getEntityBindings()) {
			for (var property : entity.getPropertyClosure()) {
				if (property.getValue() instanceof BasicValue value
					&& value.getResolution().getJdbcType().getDefaultSqlTypeCode() == SqlTypes.NAMED_ENUM) {
					Class<?> type = value.getResolution().getDomainJavaType().getJavaTypeClass();
					if (!type.isEnum()) {
						throw new HibernateException("NAMED_ENUM의 Java enum 정의가 필요합니다: " + type.getName());
					}
					List<String> labels = Arrays.stream(type.getEnumConstants())
						.map(constant -> ((Enum<?>)constant).name()).sorted().toList();
					String name = CanonicalEnumNames.sqlName(type.getSimpleName());
					List<String> prior = result.putIfAbsent(name, labels);
					if (prior != null && !prior.equals(labels)) {
						throw new HibernateException("서로 다른 enum이 같은 SQL 타입을 사용합니다: " + name);
					}
				}
			}
		}
		return result;
	}

	private void validateEnums(Connection connection, Map<String, List<String>> expected) throws SQLException {
		for (var entry : expected.entrySet()) {
			List<String> actual = new ArrayList<>();
			try (var statement = connection.prepareStatement("select enumlabel::text from pg_enum "
				+ "where enumtypid=to_regtype(?) order by enumlabel::text collate \"C\"")) {
				statement.setString(1, entry.getKey());
				try (var rows = statement.executeQuery()) {
					while (rows.next()) {
						actual.add(rows.getString(1));
					}
				}
			}
			if (!actual.equals(entry.getValue())) {
				throw new HibernateException("Existing enum " + entry.getKey()
					+ " differs from mapped labels; manual review required");
			}
		}
	}
}

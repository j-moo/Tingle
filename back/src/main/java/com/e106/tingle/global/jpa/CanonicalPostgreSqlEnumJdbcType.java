package com.e106.tingle.global.jpa;

import static org.hibernate.type.descriptor.converter.internal.EnumHelper.getEnumeratedValues;

import java.util.Set;

import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.NamedAuxiliaryDatabaseObject;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;

/** 기본 JDBC binder는 유지하되 새 enum 생성 시 Java 선언 순서를 임의 정렬하지 않는다. */
final class CanonicalPostgreSqlEnumJdbcType extends PostgreSQLEnumJdbcType {
	@Override
	@SuppressWarnings("unchecked")
	public void addAuxiliaryDatabaseObjects(JavaType<?> javaType, BasicValueConverter<?, ?> converter,
		Size size, Database database, JdbcTypeIndicators context) {
		Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>)javaType.getJavaTypeClass();
		String[] labels = converter == null ? getEnumeratedValues(enumClass)
			: getEnumeratedValues(enumClass, (BasicValueConverter<Enum<?>, ?>)converter);
		String name = CanonicalEnumNames.sqlName(enumClass.getSimpleName());
		database.addAuxiliaryDatabaseObject(new NamedAuxiliaryDatabaseObject("tingle.enum." + name,
			database.getDefaultNamespace(), database.getDialect().getCreateEnumTypeCommand(name, labels),
			new String[0], Set.of(), true));
	}
}

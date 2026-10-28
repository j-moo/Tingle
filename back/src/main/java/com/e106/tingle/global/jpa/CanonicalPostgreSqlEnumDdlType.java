package com.e106.tingle.global.jpa;

import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.type.Type;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.sql.internal.NamedNativeEnumDdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;

/** HQL cast 경로도 native enum 생성과 같은 canonical 이름을 사용한다. */
final class CanonicalPostgreSqlEnumDdlType extends NamedNativeEnumDdlTypeImpl {
	CanonicalPostgreSqlEnumDdlType(Dialect dialect) {
		super(dialect);
	}

	@Override
	public String getTypeName(Size size, Type type, DdlTypeRegistry registry) {
		return CanonicalEnumNames.sqlName(type.getReturnedClass().getSimpleName());
	}

	@Override
	public String getCastTypeName(JdbcType jdbcType, JavaType<?> javaType) {
		return CanonicalEnumNames.sqlName(javaType.getJavaTypeClass().getSimpleName());
	}

	@Override
	public String getCastTypeName(JdbcType jdbcType, JavaType<?> javaType, Long length,
		Integer precision, Integer scale) {
		return getCastTypeName(jdbcType, javaType);
	}
}

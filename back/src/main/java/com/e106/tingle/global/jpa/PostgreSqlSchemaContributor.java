package com.e106.tingle.global.jpa;

import java.util.Set;

import org.hibernate.boot.ResourceStreamLocator;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.AbstractAuxiliaryDatabaseObject;
import org.hibernate.boot.model.relational.QualifiedTableName;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.boot.spi.AdditionalMappingContributions;
import org.hibernate.boot.spi.AdditionalMappingContributor;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;

/** 엔티티의 PostgreSQL 표현식 유일성을 JPA 생성·update에도 빠짐없이 포함한다. */
public class PostgreSqlSchemaContributor implements AdditionalMappingContributor {
	@Override
	public void contribute(AdditionalMappingContributions contributions, InFlightMetadataCollector metadata,
		ResourceStreamLocator resources, MetadataBuildingContext context) {
		var profile = metadata.getEntityBinding("com.e106.tingle.user.Profile");
		if (profile == null) {
			profile = metadata.getEntityBinding("com.e106.tingle.profile.PublicProfile");
		}
		if (profile != null) {
			contributions.contributeAuxiliaryDatabaseObject(
				new VerifiedIndex(profile.getTable().getQualifiedTableName(), "uq_profile_nickname_lower",
					"(lower(nickname))", "i.indnkeyatts=1 and i.indpred is null and i.indoption[0]=0 "
						+ "and regexp_replace(pg_get_expr(i.indexprs,i.indrelid),'[()\"[:space:]]','','g') "
						+ "in ('lowernickname::text','lowernickname')"));
		}
		var survey = metadata.getEntityBindings().stream()
			.filter(entity -> entity.getTable().getName().equals("survey")).findFirst();
		if (survey.isPresent()) {
			contributions.contributeAuxiliaryDatabaseObject(
				new VerifiedIndex(survey.get().getTable().getQualifiedTableName(), "uq_survey_draft",
					"(user_id,survey_version) where completed_at is null",
					"i.indnkeyatts=2 and i.indexprs is null and i.indoption[0]=0 and i.indoption[1]=0 "
						+ "and pg_get_indexdef(i.indexrelid,1,true)='user_id' "
						+ "and pg_get_indexdef(i.indexrelid,2,true)='survey_version' "
						+ "and lower(regexp_replace(pg_get_expr(i.indpred,i.indrelid),"
						+ "'[()\"[:space:]]','','g'))='completed_atisnull'"));
		}
	}

	static final class VerifiedIndex extends AbstractAuxiliaryDatabaseObject {
		private final QualifiedTableName table;
		private final String name;
		private final String definition;
		private final String expected;

		private VerifiedIndex(QualifiedTableName table, String name, String definition, String expected) {
			super(false, Set.of());
			this.table = table;
			this.name = name;
			this.definition = definition;
			this.expected = expected;
		}

		@Override
		public boolean appliesToDialect(Dialect dialect) {
			return dialect instanceof PostgreSQLDialect;
		}

		@Override
		public String getExportIdentifier() {
			return table.render() + "." + name;
		}

		@Override
		public String[] sqlCreateStrings(SqlStringGenerationContext context) {
			String target = context.format(table);
			String index = context.format(new QualifiedTableName(table.getCatalogName(), table.getSchemaName(),
				Identifier.toIdentifier(name)));
			String indexLiteral = literal(index);
			String create = "create unique index if not exists " + name + " on " + target + " " + definition;
			String sql = "do $tingle_index$ begin "
				+ "perform pg_advisory_xact_lock(hashtext(" + indexLiteral + ")); "
				+ "if to_regclass(" + indexLiteral + ") is null then execute " + literal(create) + "; end if; "
				+ "if not exists (" + matchingSql(context) + ") then "
				+ "raise exception 'Existing " + name + " is incompatible; manual review required'; "
				+ "end if; end $tingle_index$";
			return new String[] {sql};
		}

		String validationSql(SqlStringGenerationContext context) {
			return "select exists (" + matchingSql(context) + ")";
		}

		String name() {
			return name;
		}

		private String matchingSql(SqlStringGenerationContext context) {
			String target = context.format(table);
			String index = context.format(new QualifiedTableName(table.getCatalogName(), table.getSchemaName(),
				Identifier.toIdentifier(name)));
			return "select 1 from pg_index i join pg_class c on c.oid=i.indexrelid "
				+ "join pg_am am on am.oid=c.relam where i.indexrelid=to_regclass(" + literal(index) + ") "
				+ "and i.indrelid=to_regclass(" + literal(target) + ") and i.indisunique and i.indisvalid "
				+ "and i.indisready and i.indnatts=i.indnkeyatts and not i.indnullsnotdistinct "
				+ "and am.amname='btree' and " + expected;
		}

		@Override
		public String[] sqlDropStrings(SqlStringGenerationContext context) {
			// update must not remove an existing unique index, even temporarily.
			return new String[0];
		}

		private static String literal(String value) {
			return "'" + value.replace("'", "''") + "'";
		}
	}
}

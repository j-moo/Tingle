package com.e106.tingle.global.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 늘 켜진 키링 초기화·TSV3 경로가 쓰는 JDBC 전용 표·칼럼. 엔티티가 아니라 ddl-auto 가 만들지도 검사하지도 않으므로
 * 없으면 기동은 되고 요청만 500 이 된다. 기동할 때 확인해 없으면 멈춘다(블루그린은 옛 색을 유지한다).
 * 개발·운영 DB 는 infra/db/prod-changes/2026-11-17-env-parity-407.sql, local·test 는 시작 SQL(resources/database)로 만든다.
 */
@Component
// 엔티티 칼럼은 JPA(update·create-drop)가 만든 뒤에 확인한다.
@DependsOn("entityManagerFactory")
public class RequiredSchemaCheck {

	static final List<String> TABLES = List.of("keyring_reset_run", "keyring_peer_owner", "keyring_peer_ownership",
		"keyring_peer_owner_grant", "keyring_peer_record_command", "keyring_peer_review_resolution",
		"keyring_peer_encounter");
	static final Map<String, List<String>> COLUMNS = Map.of("keyring", List.of("ownership_epoch"),
		"keyring_sync_run", List.of("ownership_epoch", "server_proofs"));

	public RequiredSchemaCheck(JdbcTemplate jdbc) {
		List<String> missing = new ArrayList<>();
		for (String table : TABLES) {
			if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table))) {
				missing.add(table);
			}
		}
		COLUMNS.forEach((table, columns) -> columns.forEach(column -> {
			if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from information_schema.columns "
				+ "where table_schema=current_schema() and table_name=? and column_name=?)", Boolean.class,
				table, column))) {
				missing.add(table + "." + column);
			}
		}));
		if (!missing.isEmpty()) {
			throw new IllegalStateException("DB 에 필요한 표·칼럼이 없습니다: " + String.join(", ", missing)
				+ " (infra/db/prod-changes/2026-11-17-env-parity-407.sql 을 먼저 실행하세요)");
		}
	}
}

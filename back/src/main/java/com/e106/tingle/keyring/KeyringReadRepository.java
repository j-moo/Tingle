package com.e106.tingle.keyring;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.keyring.dto.KeyringView;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class KeyringReadRepository {

	private final JdbcTemplate jdbc;

	public boolean hasActiveBinding(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from keyring_bindings b "
			+ "join keyrings k on k.device_id=b.device_id where b.user_id=? and b.unbound_at is null)",
			Boolean.class, userId));
	}

	public List<KeyringView> findOwned(long userId) {
		// DB의 활성 device_id 유일 제약으로 소유자는 한 명이다. 자격 정보는 SELECT하지 않는다.
		return jdbc.query("select k.public_device_id,k.firmware_version,b.bound_at,case "
			+ "when exists (select 1 from keyring_sync_runs r join settings_snapshots s "
			+ "on s.snapshot_id=r.snapshot_id join matching_settings m on m.user_id=b.user_id "
			+ "where r.binding_id=b.binding_id and s.user_id=b.user_id "
			+ "and s.settings_version=m.settings_version and r.status='ACKNOWLEDGED' "
			+ "and r.acknowledged_at is not null) then 'READY' "
			+ "when exists (select 1 from keyring_sync_runs r where r.binding_id=b.binding_id) "
			+ "or exists (select 1 from matching_settings m where m.user_id=b.user_id) "
			+ "then 'SYNC_PENDING' else 'REGISTERED' end as status "
			+ "from keyring_bindings b join keyrings k on k.device_id=b.device_id "
			+ "where b.user_id=? and b.unbound_at is null order by b.bound_at desc,k.public_device_id",
			(rs, row) -> new KeyringView(rs.getObject("public_device_id", UUID.class),
				KeyringStatus.valueOf(rs.getString("status")), rs.getTimestamp("bound_at").toInstant(),
				rs.getString("firmware_version")), userId);
	}
}

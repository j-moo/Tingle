package com.e106.tingle.keyring;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.keyring.dto.KeyringView;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class KeyringReadRepository {

	private final JdbcTemplate jdbc;

	public boolean hasActiveBinding(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from keyring "
			+ "where owner_user_id=? and state='REGISTERED')",
			Boolean.class, userId));
	}

	public List<KeyringView> findOwned(long userId) {
		// 현재 소유와 실제 ACK만 읽는다. 기기 자격 정보와 추정 등록 시각은 응답하지 않는다.
		return jdbc.query("select k.id,k.serial_number,k.firmware_version,k.label,case "
			+ "when s.settings_version>0 and k.synced_settings_version=s.settings_version then 'READY' "
			+ "when s.user_id is not null or k.synced_settings_version is not null then 'SYNC_PENDING' "
			+ "else 'REGISTERED' end as status from keyring k "
			+ "left join user_setting s on s.user_id=k.owner_user_id "
			+ "where k.owner_user_id=? and k.state='REGISTERED' order by k.id",
			(rs, row) -> new KeyringView(rs.getLong("id"), rs.getString("serial_number"),
				KeyringStatus.valueOf(rs.getString("status")), rs.getString("firmware_version"),
				KeyringView.displayLabel(rs.getString("label"), rs.getString("serial_number"))), userId);
	}

	/** 현재 주인의 등록 상태 키링만 바꾼다. 바뀐 행이 없으면 false(없는 키링·남의 키링·해제된 키링). */
	public boolean updateLabel(long userId, long deviceId, String label) {
		return jdbc.update("update keyring set label=? where id=? and owner_user_id=? and state='REGISTERED'",
			label, deviceId, userId) == 1;
	}
}

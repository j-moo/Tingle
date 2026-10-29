package com.e106.tingle.user;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 만 19세 미만으로 확인된 회원을 바로 탈퇴시킨다(가입 중 프로필 단계라 매칭·채팅 자료는 아직 없다).
 * 회원과 그 회원의 동의·세션·프로필·설문·희망 조건·기기 설정을 지우고, 키링·푸시 설치는 소유만 풀어 둔다
 * (ERD keyring.state: 탈퇴하면 소유자를 비우고 UNBOUND). 같은 소셜 계정으로 다시 로그인하면 새 회원이 된다.
 * 물리 외래 키가 없어서 지우는 순서는 상관없다.
 */
@Service
@RequiredArgsConstructor
public class UnderAgeWithdrawalService {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	@Transactional
	public void withdraw(long userId) {
		// 로그인·재발급·로그아웃과 같은 회원 행 잠금. 이미 지워졌으면 할 일이 없다.
		if (jdbc.queryForList("select id from users where id=? for update", Long.class, userId).isEmpty()) {
			return;
		}
		String surveys = "(select id from survey where user_id=?)";
		jdbc.update("delete from personality where survey_id in " + surveys, userId);
		jdbc.update("delete from survey_answer where survey_id in " + surveys, userId);
		for (String table : new String[] {"survey", "profile", "matching_preference", "user_setting",
			"user_consents", "refresh_token"}) {
			jdbc.update("delete from " + table + " where user_id=?", userId);
		}
		jdbc.update("update push_installation set user_id=null, login_family_id=null, binding_id=null, "
			+ "linked_at=null, updated_at=? where user_id=?", Timestamp.from(clock.instant()), userId);
		jdbc.update("update keyring set owner_user_id=null, state='UNBOUND', synced_settings_version=null, "
			+ "synced_at=null where owner_user_id=?", userId);
		jdbc.update("delete from users where id=?", userId);
	}
}

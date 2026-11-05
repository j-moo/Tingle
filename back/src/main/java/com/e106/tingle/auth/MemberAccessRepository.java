package com.e106.tingle.auth;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.agreement.AgreementPolicy;

@Repository
public class MemberAccessRepository {
	private static final String AGE_SELECTED = "((m.age_preference_mode=false and m.min_age is null "
		+ "and m.max_age is null) or (m.age_preference_mode=true and m.min_age>=19 "
		+ "and (m.max_age is null or m.max_age>=m.min_age)))";
	private final JdbcTemplate jdbc;
	private final Environment environment;

	public MemberAccessRepository(JdbcTemplate jdbc, Environment environment) {
		this.jdbc = jdbc;
		this.environment = environment;
	}

	// 가입 단계별 완료 여부 (OnboardingService.statusOf). 마지막 키링 반영까지 끝났는지는 ready 가 판단한다.

	public boolean hasKeyring(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from keyring "
			+ "where owner_user_id=? and state='REGISTERED')", Boolean.class, userId));
	}

	public boolean surveyCompleted(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from profile p "
			+ "join personality a on a.id=p.active_personality_id join survey s on s.id=a.survey_id "
			+ "where p.user_id=? and s.user_id=p.user_id and s.completed_at is not null and a.status='SUCCEEDED')",
			Boolean.class, userId));
	}

	public boolean preferencesSet(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from matching_preference m "
			+ "where m.user_id=? and " + AGE_SELECTED + ")", Boolean.class, userId));
	}

	// 회원이 매칭 켜짐을 끈 상태면 가입을 마쳤어도 새 매칭을 시작하지 않는다.
	public boolean matchingEnabled(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from matching_preference "
			+ "where user_id=? and matching_enabled)", Boolean.class, userId));
	}

	public void lockMember(long userId) {
		jdbc.queryForObject("select id from users where id=? for update", Long.class, userId);
	}

	public SettingsVersions settingsVersions(long userId) {
		return jdbc.queryForObject("select coalesce(max(case when s.settings_version>=0 "
			+ "then s.settings_version end),0), "
			+ "max(case when k.synced_settings_version>0 and k.synced_settings_version<=s.settings_version "
			+ "then k.synced_settings_version end) from user_setting s left join keyring k "
			+ "on k.owner_user_id=s.user_id and k.state='REGISTERED' where s.user_id=?",
			(rs, row) -> new SettingsVersions(rs.getLong(1), rs.getObject(2, Long.class)), userId);
	}

	public record SettingsVersions(long required, Long acknowledged) {
	}

	public boolean ready(long userId, Instant now) {
		if (!requiredAgreementsSatisfied(userId, signupCompleted(userId))) {
			return false;
		}
		return matchingDataReady(userId, now);
	}

	// 기존 제안 응답은 기기·현재 설문/설정 반영과 분리한다. 회원 행 잠금 후 최신 상태를 검사한다.
	public boolean canRespondToProposal(long userId) {
		return signupCompleted(userId) && requiredAgreementsSatisfied(userId, true);
	}

	public boolean signupCompleted(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from users "
			+ "where id=? and status='ACTIVE' and signup_completed_at is not null)", Boolean.class, userId));
	}

	private boolean requiredAgreementsSatisfied(long userId, boolean signupCompleted) {
		List<AgreementItem> items = Binder.get(environment)
			.bind("app.agreement.items", Bindable.listOf(AgreementItem.class)).orElse(List.of());
		List<AgreementItem> required = items.stream()
			.filter(item -> AgreementPolicy.requiredForAccess(item.type(), item.required(), signupCompleted)).toList();
		if (required.isEmpty()) {
			return false;
		}
		for (AgreementItem item : required) {
			List<Boolean> agreed = jdbc.query("select agreed and document_version=? from user_consents "
				+ "where user_id=? and consent_type=? order by recorded_at desc, consent_id desc limit 1",
				(rs, row) -> rs.getBoolean(1), item.version(), userId, item.type());
			if (agreed.isEmpty() || !agreed.getFirst()) {
				return false;
			}
		}
		return true;
	}

	private boolean matchingDataReady(long userId, Instant now) {
		LocalDate adultBefore = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().minusYears(19);
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from users u "
			+ "join profile p on p.user_id=u.id join matching_preference m on m.user_id=u.id "
			+ "join user_setting us on us.user_id=u.id "
			+ "join personality a on a.id=p.active_personality_id join survey s on s.id=a.survey_id "
			+ "where u.id=? and u.status='ACTIVE' and p.nickname is not null "
			+ "and p.birth_date<=? and p.gender in ('MALE','FEMALE') and p.smoking_status is not null "
			+ "and p.drinking_frequency is not null and p.completed_at is not null and " + AGE_SELECTED
			+ " and s.user_id=u.id and s.completed_at is not null and a.status='SUCCEEDED' "
			+ "and us.settings_version>0 and exists (select 1 from keyring k where k.owner_user_id=u.id "
			+ "and k.state='REGISTERED' and k.synced_settings_version=us.settings_version))",
			Boolean.class, userId, java.sql.Date.valueOf(adultBefore)));
	}

	public boolean restrictedPair(long first, long second) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from matching m "
			+ "join matching_participant p on p.matching_id=m.id "
			+ "where ((m.user_low_id=? and m.user_high_id=?) or (m.user_low_id=? and m.user_high_id=?)) "
			+ "and p.user_id in (m.user_low_id,m.user_high_id) and p.blocked_at is not null) "
			+ "or exists (select 1 from matching m where m.user_low_id=least(?,?) and m.user_high_id=greatest(?,?) "
			+ "and (m.status='CLOSED' or exists (select 1 from chat c where c.matching_id=m.id "
			+ "and c.close_reason='LEFT'))) "
			+ "or exists (select 1 from users where id in (?,?) and status<>'ACTIVE')",
			Boolean.class, first, second, second, first, first, second, first, second, first, second));
	}

	public record AgreementItem(String type, String version, boolean required) {
	}
}

package com.e106.tingle.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.agreement.AgreementPolicy;

@Repository
public class MemberAccessRepository {
	private final JdbcTemplate jdbc;
	private final Environment environment;

	public MemberAccessRepository(JdbcTemplate jdbc, Environment environment) {
		this.jdbc = jdbc;
		this.environment = environment;
	}

	// 가입 단계별 완료 여부 (OnboardingService.statusOf). 마지막 키링 반영까지 끝났는지는 ready 가 판단한다.

	public boolean hasKeyring(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from keyring_bindings "
			+ "where user_id=? and unbound_at is null)", Boolean.class, userId));
	}

	public boolean surveyCompleted(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from survey_responses sr "
			+ "join analysis_results a on a.response_id=sr.response_id where sr.user_id=? "
			+ "and sr.completed_at is not null and a.outcome='SUCCEEDED' "
			+ "and sr.response_id=(select max(response_id) from survey_responses "
			+ "where user_id=? and completed_at is not null))",
			Boolean.class, userId, userId));
	}

	public boolean preferencesSet(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from matching_settings "
			+ "where user_id=? and cardinality(desired_genders)>0 and age_min>=19)", Boolean.class, userId));
	}

	public void lockMember(long userId) {
		jdbc.queryForObject("select user_id from app_users where user_id=? for update", Long.class, userId);
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
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from app_users "
			+ "where user_id=? and status='ACTIVE' and signup_completed_at is not null)", Boolean.class, userId));
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
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from app_users u "
			+ "join profiles p on p.user_id=u.user_id join matching_settings m on m.user_id=u.user_id "
			+ "where u.user_id=? and u.status='ACTIVE' and p.nickname is not null and p.nickname_key is not null "
			+ "and p.birth_date<=? and p.gender in ('MALE','FEMALE') and p.smoking_status is not null "
			+ "and p.drinking_frequency is not null and p.completed_at is not null "
			+ "and cardinality(m.desired_genders)>0 and m.age_min>=19 "
			+ "and exists (select 1 from survey_responses sr join analysis_results a on a.response_id=sr.response_id "
			+ "join settings_snapshots ss on ss.analysis_result_id=a.result_id "
			+ "join keyring_sync_runs k on k.snapshot_id=ss.snapshot_id "
			+ "join keyring_bindings b on b.binding_id=k.binding_id "
			+ "where sr.user_id=u.user_id and sr.completed_at is not null and a.outcome='SUCCEEDED' "
			+ "and sr.response_id=(select max(response_id) from survey_responses "
			+ "where user_id=u.user_id and completed_at is not null) "
			+ "and ss.user_id=u.user_id and ss.settings_version=m.settings_version "
			+ "and b.user_id=u.user_id and b.unbound_at is null "
			+ "and k.status='ACKNOWLEDGED' and k.acknowledged_at is not null))",
			Boolean.class, userId, java.sql.Date.valueOf(adultBefore)));
	}

	public boolean restrictedPair(long first, long second) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from user_blocks "
			+ "where (blocker_id=? and blocked_id=?) or (blocker_id=? and blocked_id=?)) "
			+ "or exists (select 1 from app_users where user_id in (?,?) and status<>'ACTIVE')",
			Boolean.class, first, second, second, first, first, second));
	}

	public record AgreementItem(String type, String version, boolean required) {
	}
}

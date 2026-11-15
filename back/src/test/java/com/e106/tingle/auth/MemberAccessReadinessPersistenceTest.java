package com.e106.tingle.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;

import com.e106.tingle.SharedPostgres;

/**
 * 실제 canonical SQL과 가입 준비 조회의 회귀다. 준비 증거를 명시적으로 넣는 fixture이며,
 * 실제 분석·키링 ACK 전송이나 OnboardingService의 기존 가입자 접근 정책을 검증하는 테스트는 아니다.
 */
class MemberAccessReadinessPersistenceTest {

	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String VERSION = "2026-10-19";
	private static final List<String> REQUIRED = List.of("TERMS", "PRIVACY", "AI_RISK_DETECTION", "ADULT");

	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();

	private static JdbcTemplate jdbc;
	private static MemberAccessRepository members;

	@BeforeAll
	static void createDisposableCanonicalSchema() {
		DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql")).execute(source);
		jdbc = new JdbcTemplate(source);
		MockEnvironment environment = new MockEnvironment();
		for (int index = 0; index < REQUIRED.size(); index++) {
			String prefix = "app.agreement.items[" + index + "].";
			environment.setProperty(prefix + "type", REQUIRED.get(index));
			environment.setProperty(prefix + "version", VERSION);
			environment.setProperty(prefix + "required", "true");
		}
		members = new MemberAccessRepository(jdbc, environment);
	}

	@Test
	@DisplayName("본인 완료 설문·성공 분석·등록 성인 프로필·명시 조건·현재 소유 ACK·동의가 모두 있어야 준비된다")
	void acceptsOnlyCompleteCanonicalReadinessEvidence() {
		Evidence evidence = readyMember();
		assertThat(members.hasKeyring(evidence.userId())).isTrue();
		assertThat(members.surveyCompleted(evidence.userId())).isTrue();
		assertThat(members.preferencesSet(evidence.userId())).isTrue();
		assertThat(members.signupCompleted(evidence.userId())).isFalse();
		assertThat(members.ready(evidence.userId(), NOW)).isTrue();
	}

	@Test
	@DisplayName("마지막 키링을 초기화한 가입 완료 회원은 기존 제안 응답은 되지만 새 제안은 받지 않는다(키링 필수일 때만)")
	void keyringlessCompletedMemberGetsNoNewProposals() {
		Evidence evidence = readyMember();
		jdbc.update("update users set signup_completed_at=? where id=?",
			java.sql.Timestamp.from(NOW), evidence.userId());
		assertThat(members.canReceiveNewProposal(evidence.userId())).isTrue();
		jdbc.update("update keyring set owner_user_id=null,state='UNBOUND' where owner_user_id=?", evidence.userId());
		assertThat(members.canRespondToProposal(evidence.userId())).isTrue();
		assertThat(members.canReceiveNewProposal(evidence.userId())).isFalse();
		MockEnvironment optional = new MockEnvironment();
		for (int index = 0; index < REQUIRED.size(); index++) {
			String prefix = "app.agreement.items[" + index + "].";
			optional.setProperty(prefix + "type", REQUIRED.get(index));
			optional.setProperty(prefix + "version", VERSION);
			optional.setProperty(prefix + "required", "true");
		}
		optional.setProperty("app.signup.keyring-required", "false");
		assertThat(new MemberAccessRepository(jdbc, optional).canReceiveNewProposal(evidence.userId())).isTrue();
	}

	@Test
	@DisplayName("다른 회원의 적용 포인터나 PENDING·FAILED·INSUFFICIENT 분석은 성공 증거가 아니다")
	void rejectsForeignOrUnsuccessfulActivePersonality() {
		Evidence owner = readyMember();
		Evidence other = readyMember();
		jdbc.update("update profile set active_personality_id=? where user_id=?",
			other.personalityId(), owner.userId());
		assertThat(members.surveyCompleted(owner.userId())).isFalse();
		assertThat(members.ready(owner.userId(), NOW)).isFalse();
		for (String status : List.of("PENDING", "FAILED", "INSUFFICIENT")) {
			Evidence evidence = readyMember();
			jdbc.update("update personality set status=cast(? as analysis_status) where id=?",
				status, evidence.personalityId());
			assertThat(members.surveyCompleted(evidence.userId())).isFalse();
			assertThat(members.ready(evidence.userId(), NOW)).isFalse();
		}
	}

	@Test
	@DisplayName("설문 미완료나 적용 포인터 유실을 완료·성공으로 추정하지 않는다")
	void rejectsUnfinishedSurveyAndMissingActivePointer() {
		Evidence unfinished = readyMember();
		jdbc.update("update survey set completed_at=null where id=?", unfinished.surveyId());
		assertThat(members.surveyCompleted(unfinished.userId())).isFalse();
		assertThat(members.ready(unfinished.userId(), NOW)).isFalse();
		Evidence missing = readyMember();
		jdbc.update("update profile set active_personality_id=null where user_id=?", missing.userId());
		assertThat(members.surveyCompleted(missing.userId())).isFalse();
		assertThat(members.ready(missing.userId(), NOW)).isFalse();
	}

	@Test
	@DisplayName("희망 나이는 직접 선택해야 하며 상관없음 선택도 명시적인 NULL 범위여야 한다")
	void requiresExplicitAndValidAgePreference() {
		Evidence evidence = readyMember();
		jdbc.update("update matching_preference set age_preference_mode=null where user_id=?", evidence.userId());
		assertThat(members.preferencesSet(evidence.userId())).isFalse();
		assertThat(members.ready(evidence.userId(), NOW)).isFalse();
		jdbc.update("update matching_preference set age_preference_mode=true,min_age=18 where user_id=?",
			evidence.userId());
		assertThat(members.preferencesSet(evidence.userId())).isFalse();
		jdbc.update("update matching_preference set min_age=25,max_age=24 where user_id=?", evidence.userId());
		assertThat(members.preferencesSet(evidence.userId())).isFalse();
		jdbc.update("update matching_preference set age_preference_mode=false,min_age=null,max_age=null "
			+ "where user_id=?", evidence.userId());
		assertThat(members.preferencesSet(evidence.userId())).isTrue();
		assertThat(members.ready(evidence.userId(), NOW)).isTrue();
	}

	@Test
	@DisplayName("프로필 초안·미성년·필수 성별 유실은 가입 준비 완료가 아니다")
	void requiresRegisteredAdultProfileWithRequiredFields() {
		Evidence draft = readyMember();
		jdbc.update("update profile set completed_at=null where user_id=?", draft.userId());
		assertThat(members.ready(draft.userId(), NOW)).isFalse();
		Evidence minor = readyMember();
		jdbc.update("update profile set birth_date=date '2010-01-01' where user_id=?", minor.userId());
		assertThat(members.ready(minor.userId(), NOW)).isFalse();
		Evidence missing = readyMember();
		jdbc.update("update profile set gender=null where user_id=?", missing.userId());
		assertThat(members.ready(missing.userId(), NOW)).isFalse();
	}

	@Test
	@DisplayName("설정 유실·0 버전·구 버전 ACK·타인 기기·UNBOUND는 현재 적용 증거가 아니다")
	void requiresPositiveSettingsAndCurrentOwnedRegisteredAcknowledgement() {
		Evidence missing = readyMember();
		jdbc.update("delete from user_setting where user_id=?", missing.userId());
		assertThat(members.ready(missing.userId(), NOW)).isFalse();
		Evidence zero = readyMember();
		jdbc.update("update user_setting set settings_version=0 where user_id=?", zero.userId());
		jdbc.update("update keyring set synced_settings_version=0 where id=?", zero.keyringId());
		assertThat(members.ready(zero.userId(), NOW)).isFalse();
		Evidence stale = readyMember();
		jdbc.update("update user_setting set settings_version=2 where user_id=?", stale.userId());
		assertThat(members.ready(stale.userId(), NOW)).isFalse();
		jdbc.update("update keyring set synced_settings_version=2 where id=?", stale.keyringId());
		assertThat(members.ready(stale.userId(), NOW)).isTrue();
		Evidence foreign = readyMember();
		jdbc.update("update keyring set owner_user_id=? where id=?", stale.userId(), foreign.keyringId());
		assertThat(members.hasKeyring(foreign.userId())).isFalse();
		assertThat(members.ready(foreign.userId(), NOW)).isFalse();
		Evidence unbound = readyMember();
		jdbc.update("update keyring set state='UNBOUND' where id=?", unbound.keyringId());
		assertThat(members.hasKeyring(unbound.userId())).isFalse();
		assertThat(members.ready(unbound.userId(), NOW)).isFalse();
		Evidence unknown = readyMember();
		jdbc.update("update keyring set synced_settings_version=null where id=?", unknown.keyringId());
		assertThat(members.ready(unknown.userId(), NOW)).isFalse();
	}

	@Test
	@DisplayName("현재 필수 동의의 최신 버전·철회를 검사하고 항목 설정이 비어도 통과시키지 않는다")
	void requiresCurrentLatestRequiredConsentsAndConfiguredPolicy() {
		Evidence oldVersion = readyMember();
		recordConsent(oldVersion.userId(), "TERMS", "old-version", true, NOW.plusSeconds(1));
		assertThat(members.ready(oldVersion.userId(), NOW)).isFalse();
		for (String type : REQUIRED) {
			Evidence revoked = readyMember();
			recordConsent(revoked.userId(), type, VERSION, false, NOW.plusSeconds(1));
			assertThat(members.ready(revoked.userId(), NOW)).isFalse();
		}
		Evidence configured = readyMember();
		MemberAccessRepository unconfigured = new MemberAccessRepository(jdbc, new MockEnvironment());
		assertThat(unconfigured.ready(configured.userId(), NOW)).isFalse();
	}

	@Test
	@DisplayName("동의와 준비 자료가 있어도 정지·탈퇴 대기·탈퇴 회원을 ACTIVE로 되살리지 않는다")
	void rejectsSuspendedAndWithdrawnMembers() {
		for (String status : List.of("SUSPENDED", "WITHDRAWAL_PENDING", "WITHDRAWN")) {
			Evidence evidence = readyMember();
			jdbc.update("update users set status=?,signup_completed_at=? where id=?",
				status, Timestamp.from(NOW), evidence.userId());
			assertThat(members.signupCompleted(evidence.userId())).isFalse();
			assertThat(members.ready(evidence.userId(), NOW)).isFalse();
		}
	}

	@Test
	@DisplayName("차단은 해당 쌍의 참여자 증거로 양방향 제한하고 타인 증거와 구 테이블에 의존하지 않는다")
	void restrictsPairFromCanonicalParticipantAndCurrentMemberStatus() {
		Evidence first = readyMember();
		Evidence second = readyMember();
		Evidence outsider = readyMember();
		long low = Math.min(first.userId(), second.userId());
		long high = Math.max(first.userId(), second.userId());
		Long matchingId = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'WAITING') returning id", Long.class, low, high);
		assertThat(members.restrictedPair(low, high)).isFalse();
		jdbc.update("insert into matching_participant(matching_id,user_id,blocked_at) values (?,?,?)",
			matchingId, outsider.userId(), Timestamp.from(NOW));
		assertThat(members.restrictedPair(low, high)).isFalse();
		jdbc.update("insert into matching_participant(matching_id,user_id,blocked_at) values (?,?,?)",
			matchingId, first.userId(), Timestamp.from(NOW));
		assertThat(members.restrictedPair(low, high)).isTrue();
		assertThat(members.restrictedPair(high, low)).isTrue();
		jdbc.update("update matching_participant set blocked_at=null where matching_id=?", matchingId);
		assertThat(members.restrictedPair(low, high)).isFalse();
		jdbc.update("update users set status='SUSPENDED' where id=?", high);
		assertThat(members.restrictedPair(low, high)).isTrue();
	}

	private Evidence readyMember() {
		Long userId = jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class,
			UUID.randomUUID().toString(), Timestamp.from(NOW));
		Long surveyId = jdbc.queryForObject("insert into survey(user_id,survey_version,completed_at) "
			+ "values (?,'test-survey',?) returning id", Long.class, userId, Timestamp.from(NOW));
		Long personalityId = jdbc.queryForObject("insert into personality(survey_id,analysis_version,status,result) "
			+ "values (?,'test-analysis','SUCCEEDED','{}') returning id", Long.class, surveyId);
		jdbc.update("insert into profile(user_id,nickname,birth_date,gender,smoking_status,drinking_frequency,"
			+ "completed_at,active_personality_id) values (?,?,date '2001-03-15','FEMALE','NON_SMOKER','NEVER',?,?)",
			userId, "준비" + userId, Timestamp.from(NOW), personalityId);
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version) "
			+ "values (?,true,true,1)", userId);
		jdbc.update("insert into matching_preference(user_id,matching_enabled,age_preference_mode,min_age,"
			+ "max_age,photo_required) values (?,true,true,19,null,false)", userId);
		Long keyringId = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,"
			+ "state,synced_settings_version,synced_at) values (?,'test',?,'REGISTERED',1,?) returning id",
			Long.class, UUID.randomUUID().toString(), userId, Timestamp.from(NOW));
		for (String type : REQUIRED) {
			recordConsent(userId, type, VERSION, true, NOW);
		}
		assertThat(members.ready(userId, NOW)).isTrue();
		return new Evidence(userId, surveyId, personalityId, keyringId);
	}

	private void recordConsent(long userId, String type, String version, boolean agreed, Instant recordedAt) {
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,?,?,?,?)", userId, type, version, agreed, Timestamp.from(recordedAt));
	}

	private record Evidence(long userId, long surveyId, long personalityId, long keyringId) {
	}
}

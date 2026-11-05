package com.e106.tingle.settings.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;

import tools.jackson.databind.JsonNode;

/** 실제 HTTP·JWT·PostgreSQL에서 서울 날짜 재확인과 기존 ACK/제안 보존을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SettingsAgeRefreshIntegrationTest extends IntegrationTestSupport {

	private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-10-29T14:59:58Z");
	private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-10-29T15:00:01Z");

	@Autowired
	private OnboardingService onboarding;

	@Test
	void onboardingRefreshesTheSeoulDateOnceWithoutChangingAckOrExistingProposal() throws Exception {
		Ready ready = readyBeforeMidnight();
		long other = userIdOf(login("kakao", newSocialId()));
		long matchingId = jdbc.queryForObject("insert into matching "
			+ "(user_low_id,user_high_id,status,proposed_at) values (?,?,'PENDING',now()) returning id",
			Long.class, Math.min(ready.userId(), other), Math.max(ready.userId(), other));
		jdbc.update("insert into matching_participant "
			+ "(matching_id,user_id,response,responded_at,decision_request_key) values (?,?,'ACCEPTED',now(),?)",
			matchingId, ready.userId(), UUID.randomUUID());
		Map<String, Object> proposal = jdbc.queryForMap("select * from matching where id=?", matchingId);
		Map<String, Object> response = jdbc.queryForMap(
			"select * from matching_participant where matching_id=? and user_id=?", matchingId, ready.userId());
		Map<String, Object> ack = ack(ready.userId());
		given(clock.instant()).willReturn(AFTER_MIDNIGHT);

		perform(get(me(ready.tokens(), "/onboarding")), ready.tokens(), null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.nextStep").value("SYNC_PENDING"))
			.andExpect(jsonPath("$.matchingReady").value(false))
			.andExpect(jsonPath("$.signupCompleted").value(true))
			.andExpect(jsonPath("$.requiredSettingsVersion").value(ready.version() + 1))
			.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(ready.version()));
		assertSnapshotDate(ready.userId(), ready.version() + 1, "2026-10-30");
		assertThat(ack(ready.userId())).isEqualTo(ack);
		assertThat(jdbc.queryForMap("select * from matching where id=?", matchingId)).isEqualTo(proposal);
		assertThat(jdbc.queryForMap("select * from matching_participant where matching_id=? and user_id=?",
			matchingId, ready.userId())).isEqualTo(response);
		assertAllReadsKeepTheVersion(ready, ready.version() + 1);
	}

	@Test
	void settingsGetRefreshesBeforeReturningItsNewEtagAndPreferencesReuseIt() throws Exception {
		Ready ready = readyBeforeMidnight();
		Map<String, Object> ack = ack(ready.userId());
		given(clock.instant()).willReturn(AFTER_MIDNIGHT);
		perform(get(me(ready.tokens(), "/settings")), ready.tokens(), null)
			.andExpect(status().isOk()).andExpect(header().string("ETag", etag(ready.version() + 1)))
			.andExpect(jsonPath("$.settingsVersion").value(ready.version() + 1));
		perform(get(me(ready.tokens(), "/preferences")), ready.tokens(), null)
			.andExpect(status().isOk()).andExpect(header().string("ETag", etag(ready.version() + 1)));
		assertThat(version(ready.userId())).isEqualTo(ready.version() + 1);
		assertThat(ack(ready.userId())).isEqualTo(ack);
		assertSnapshotDate(ready.userId(), ready.version() + 1, "2026-10-30");
	}

	@Test
	void readOnlyAuthorizationDoesNotWriteAndPreferencesGetPerformsTheRefresh() throws Exception {
		Ready ready = readyBeforeMidnight();
		given(clock.instant()).willReturn(AFTER_MIDNIGHT);
		onboarding.statusOf(ready.userId());
		onboarding.canAccess(ready.userId(), OnboardingStatus.PREFERENCES_REQUIRED);
		assertThat(version(ready.userId())).isEqualTo(ready.version());
		assertThat(snapshotCount(ready.userId(), ready.version() + 1)).isZero();

		perform(get(me(ready.tokens(), "/preferences")), ready.tokens(), null)
			.andExpect(status().isOk()).andExpect(header().string("ETag", etag(ready.version() + 1)));
		assertAllReadsKeepTheVersion(ready, ready.version() + 1);
		assertSnapshotDate(ready.userId(), ready.version() + 1, "2026-10-30");
	}

	@Test
	void readsBeforeSeoulMidnightWithoutAdvancingTheGeneration() throws Exception {
		Ready ready = readyBeforeMidnight();
		given(clock.instant()).willReturn(Instant.parse("2026-10-29T14:59:59Z"));
		assertAllReadsKeepTheVersion(ready, ready.version());
		assertSnapshotDate(ready.userId(), ready.version(), "2026-10-29");
		assertThat(snapshotCount(ready.userId(), ready.version() + 1)).isZero();
	}

	@Test
	void firstReadyReadReturnsTheFreshSignupCompletion() throws Exception {
		Ready ready = readyBeforeMidnight();
		jdbc.update("update users set signup_completed_at=null where id=?", ready.userId());
		perform(get(me(ready.tokens(), "/onboarding")), ready.tokens(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value("READY"))
			.andExpect(jsonPath("$.matchingReady").value(true))
			.andExpect(jsonPath("$.signupCompleted").value(true));
		assertThat(jdbc.queryForObject("select signup_completed_at is not null from users where id=?",
			Boolean.class, ready.userId())).isTrue();
		assertThat(version(ready.userId())).isEqualTo(ready.version());
	}

	@Test
	void firstReadyReadKeepsMatchingOffAndReturnsTheFreshSignupCompletion() throws Exception {
		Ready ready = readyBeforeMidnight();
		jdbc.update("update users set signup_completed_at=null where id=?", ready.userId());
		jdbc.update("update matching_preference set matching_enabled=false where user_id=?", ready.userId());
		perform(get(me(ready.tokens(), "/onboarding")), ready.tokens(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value("READY"))
			.andExpect(jsonPath("$.onboardingCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(false))
			.andExpect(jsonPath("$.signupCompleted").value(true));
		assertThat(jdbc.queryForObject("select signup_completed_at is not null from users where id=?",
			Boolean.class, ready.userId())).isTrue();
		assertThat(version(ready.userId())).isEqualTo(ready.version());
	}

	@Test
	void earlyOnboardingWithoutASettingDoesNotCreateAnInitialGeneration() throws Exception {
		given(clock.instant()).willReturn(BEFORE_MIDNIGHT);
		JsonNode tokens = login("kakao", newSocialId());
		long userId = userIdOf(tokens);
		jdbc.update("delete from user_setting where user_id=?", userId);
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value("AGREEMENT_REQUIRED"))
			.andExpect(jsonPath("$.requiredSettingsVersion").value(0));
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=?",
			Long.class, userId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=?",
			Long.class, userId)).isZero();
	}

	@Test
	void concurrentSettingsAndOnboardingReadsCreateOnlyOneNewGeneration() throws Exception {
		Ready ready = readyBeforeMidnight();
		given(clock.instant()).willReturn(AFTER_MIDNIGHT);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Integer> settings = workers.submit(() -> readAfter(start, ready.tokens(), "/settings"));
			Future<Integer> progress = workers.submit(() -> readAfter(start, ready.tokens(), "/onboarding"));
			start.countDown();
			assertThat(List.of(settings.get(20, TimeUnit.SECONDS), progress.get(20, TimeUnit.SECONDS)))
				.containsExactly(200, 200);
		}
		assertThat(version(ready.userId())).isEqualTo(ready.version() + 1);
		assertThat(snapshotCount(ready.userId(), ready.version() + 1)).isEqualTo(1);
		assertAllReadsKeepTheVersion(ready, ready.version() + 1);
	}

	private Ready readyBeforeMidnight() throws Exception {
		given(clock.instant()).willReturn(BEFORE_MIDNIGHT);
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);
		long userId = userIdOf(tokens);
		return new Ready(tokens, userId, version(userId));
	}

	private void assertAllReadsKeepTheVersion(Ready ready, long expected) throws Exception {
		perform(get(me(ready.tokens(), "/onboarding")), ready.tokens(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.requiredSettingsVersion").value(expected));
		perform(get(me(ready.tokens(), "/settings")), ready.tokens(), null).andExpect(status().isOk())
			.andExpect(header().string("ETag", etag(expected)));
		perform(get(me(ready.tokens(), "/preferences")), ready.tokens(), null).andExpect(status().isOk())
			.andExpect(header().string("ETag", etag(expected)));
		assertThat(version(ready.userId())).isEqualTo(expected);
	}

	private void assertSnapshotDate(long userId, long version, String date) throws Exception {
		String source = jdbc.queryForObject("select input_json from settings_snapshot "
			+ "where user_id=? and settings_version=?", String.class, userId, version);
		assertThat(jsonMapper.readTree(source).path("ageReferenceDate").asString()).isEqualTo(date);
	}

	private long snapshotCount(long userId, long version) {
		return jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? and settings_version=?",
			Long.class, userId, version);
	}

	private long version(long userId) {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=?", Long.class, userId);
	}

	private Map<String, Object> ack(long userId) {
		return jdbc.queryForMap("select synced_settings_version,synced_at from keyring where owner_user_id=?", userId);
	}

	private int readAfter(CountDownLatch start, JsonNode tokens, String path) throws Exception {
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("HTTP read start timed out");
		}
		return perform(get(me(tokens, path)), tokens, null).andReturn().getResponse().getStatus();
	}

	private static String etag(long version) {
		return "\"" + version + "\"";
	}

	private record Ready(JsonNode tokens, long userId, long version) {
	}
}

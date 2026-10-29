package com.e106.tingle.settings.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.settings.UserSetting;
import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.ProfileAnalysisApplicationService;
import com.e106.tingle.user.ProfileRepository;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import tools.jackson.databind.json.JsonMapper;

/** 포인터의 비교·교환과 늦은 결과/모호한 순서의 적용 거부를 검증한다. 실제 DB 원자성은 통합 검사 대상이다. */
class ProfileAnalysisApplicationTest {

	private final UserRepository users = mock(UserRepository.class);
	private final ProfileRepository profiles = mock(ProfileRepository.class);
	private final SettingsChangeCoordinator changes = mock(SettingsChangeCoordinator.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final SurveyCatalog catalog = mock(SurveyCatalog.class);
	private final Profile profile = mock(Profile.class);
	private final ProfileAnalysisApplicationService service = new ProfileAnalysisApplicationService(
		users, profiles, changes, jdbc, catalog, new SettingsSnapshotJson(), JsonMapper.builder().build());

	@BeforeEach
	void prepare() {
		User user = mock(User.class);
		given(user.getStatus()).willReturn(UserStatus.ACTIVE);
		given(users.findByIdForUpdate(7L)).willReturn(Optional.of(user));
		given(profiles.findById(7L)).willReturn(Optional.of(profile));
		given(profile.getActivePersonalityId()).willReturn(null);
		given(changes.lockIfPresent(7)).willReturn(Optional.of(mock(UserSetting.class)));
		given(catalog.find("survey-v1")).willReturn(Optional.of(mock(SurveyCatalog.SurveyDefinition.class)));
		given(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any(Timestamp.class))).willReturn(0L);
		given(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any(Timestamp.class), eq(12L))).willReturn(0L);
	}

	@Test
	void successfulActivationChangesThePointerThenAdvancesAndCapturesOnce() {
		Profile realProfile = createProfile(4L);
		given(profiles.findById(7L)).willReturn(Optional.of(realProfile));
		source(12, "2026-10-29T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		source(4, "2026-10-28T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		assertThat(service.apply(7, 12, 4L)).isTrue();
		assertThat(realProfile.getActivePersonalityId()).isEqualTo(12);
		verify(changes).advanceAndCapture(7);
	}

	@Test
	void alreadyAppliedResultIsAnIdempotentNoOpEvenWithOriginalExpectedPointer() {
		given(profile.getActivePersonalityId()).willReturn(12L);
		assertThat(service.apply(7, 12, 4L)).isFalse();
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void rejectsResultFromAnOlderCalculationWithUnexpectedCurrentPointer() {
		given(profile.getActivePersonalityId()).willReturn(8L);
		assertError(() -> service.apply(7, 12, 4L), ErrorCode.PRECONDITION_FAILED);
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void olderCompletedSurveyCannotReplaceTheAppliedNewerSurvey() {
		given(profile.getActivePersonalityId()).willReturn(4L);
		source(12, "2026-10-28T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		source(4, "2026-10-29T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		assertError(() -> service.apply(7, 12, 4L), ErrorCode.PRECONDITION_FAILED);
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void simultaneousResultsHaveNoInventedVersionOrIdOrdering() {
		given(profile.getActivePersonalityId()).willReturn(4L);
		source(12, "2026-10-29T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		source(4, "2026-10-29T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		assertError(() -> service.apply(7, 12, 4L), ErrorCode.SERVICE_UNAVAILABLE);
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void anotherOwnersResultAndPendingOrFailedResultCannotBeApplied() {
		for (String status : List.of("PENDING", "FAILED", "INSUFFICIENT")) {
			source(12, "2026-10-29T00:00:00Z", 7, status, "{\"result\":true}");
			assertError(() -> service.apply(7, 12, null), ErrorCode.INVALID_REQUEST);
		}
		source(12, "2026-10-29T00:00:00Z", 8, "SUCCEEDED", "{\"result\":true}");
		assertError(() -> service.apply(7, 12, null), ErrorCode.INVALID_REQUEST);
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void emptyOrMalformedResultsDoNotBecomeAnAppliedSuccess() {
		for (String json : List.of("{}", "[]", "null", "{\"x\":1,\"x\":2}", "{")) {
			source(12, "2026-10-29T00:00:00Z", 7, "SUCCEEDED", json);
			assertError(() -> service.apply(7, 12, null), ErrorCode.INVALID_REQUEST);
		}
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void newerOrAmbiguousCompletedSuccessesBlockAnOldCandidate() {
		source(12, "2026-10-29T00:00:00Z", 7, "SUCCEEDED", "{\"result\":true}");
		given(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any(Timestamp.class))).willReturn(1L);
		assertError(() -> service.apply(7, 12, null), ErrorCode.PRECONDITION_FAILED);
		given(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any(Timestamp.class))).willReturn(0L);
		given(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any(Timestamp.class), eq(12L))).willReturn(1L);
		assertError(() -> service.apply(7, 12, null), ErrorCode.SERVICE_UNAVAILABLE);
		verify(changes, never()).advanceAndCapture(7);
	}

	@Test
	void missingSettingsCannotCommitAPointerWithoutItsGeneration() {
		given(changes.lockIfPresent(7)).willReturn(Optional.empty());
		assertError(() -> service.apply(7, 12, null), ErrorCode.SERVICE_UNAVAILABLE);
		verify(changes, never()).advanceAndCapture(7);
	}

	private void source(long id, String completedAt, long owner, String status, String result) {
		Map<String, Object> row = new HashMap<>();
		row.put("user_id", owner);
		row.put("completed_at", Timestamp.from(Instant.parse(completedAt)));
		row.put("survey_version", "survey-v1");
		row.put("analysis_version", "analysis-v1");
		row.put("status", status);
		row.put("result", result);
		given(jdbc.queryForList(anyString(), eq(id))).willReturn(List.of(row));
	}

	private static Profile createProfile(Long active) {
		Profile profile = BeanUtils.instantiateClass(Profile.class);
		ReflectionTestUtils.setField(profile, "userId", 7L);
		ReflectionTestUtils.setField(profile, "activePersonalityId", active);
		return profile;
	}

	private static void assertError(Runnable action, ErrorCode error) {
		assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode()).isEqualTo(error));
	}
}

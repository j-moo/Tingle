package com.e106.tingle.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.e106.tingle.agreement.dto.AgreementAnswer;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

/**
 * 동의 항목 규칙. 설정에 선택 항목(MARKETING)을 추가한 상황으로, 코드 수정 없이 설정만으로 동작하는지 확인한다.
 */
class AgreementServiceTest {

	private static final String V = "v1";

	private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

	private UserConsentRepository repository;
	private UserRepository userRepository;
	private Clock clock;
	private AgreementService service;

	@BeforeEach
	void setUp() {
		repository = mock(UserConsentRepository.class);
		userRepository = mock(UserRepository.class);
		given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(mock(User.class)));
		clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
		AgreementProperties properties = new AgreementProperties(List.of(
			new AgreementProperties.Item("TERMS", V, true, "https://example.com/terms"),
			new AgreementProperties.Item("ADULT", V, true, null),
			new AgreementProperties.Item("MARKETING", V, false, null)));
		service = new AgreementService(properties, repository, userRepository, clock);
	}

	@Test
	@DisplayName("필수 항목에 모두 동의하면 선택 항목은 동의하지 않아도 통과한다")
	void optionalItemMayBeDeclined() {
		assertThatCode(() -> service.validate(answers(true, true, false))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("필수 항목에 동의하지 않으면 REQUIRED_AGREEMENT_MISSING")
	void declinedRequiredItemIsMissing() {
		assertErrorCode(() -> service.validate(answers(true, false, true)), ErrorCode.REQUIRED_AGREEMENT_MISSING);
	}

	@Test
	@DisplayName("버전이 다르면 AGREEMENT_VERSION_OUTDATED")
	void differentVersionIsOutdated() {
		List<AgreementAnswer> answers = List.of(
			new AgreementAnswer("TERMS", "old", true),
			new AgreementAnswer("ADULT", V, true),
			new AgreementAnswer("MARKETING", V, false));

		assertErrorCode(() -> service.validate(answers), ErrorCode.AGREEMENT_VERSION_OUTDATED);
	}

	@Test
	@DisplayName("현재 항목이 빠져 있으면 앱이 오래된 목록을 보여 준 것이므로 AGREEMENT_VERSION_OUTDATED")
	void missingCurrentItemIsOutdated() {
		List<AgreementAnswer> answers = List.of(
			new AgreementAnswer("TERMS", V, true),
			new AgreementAnswer("ADULT", V, true));

		assertErrorCode(() -> service.validate(answers), ErrorCode.AGREEMENT_VERSION_OUTDATED);
	}

	@Test
	@DisplayName("빈 목록을 보내면 현재 항목이 모두 빠진 것이므로 AGREEMENT_VERSION_OUTDATED")
	void emptyAnswersAreOutdated() {
		assertErrorCode(() -> service.validate(List.of()), ErrorCode.AGREEMENT_VERSION_OUTDATED);
	}

	@Test
	@DisplayName("설정에 항목이 하나도 없으면 빈 목록으로 가입할 수 있다")
	void emptyAnswersAllowedWhenNoItemsConfigured() {
		AgreementService noItems = new AgreementService(new AgreementProperties(List.of()), repository,
			userRepository, clock);

		assertThatCode(() -> noItems.validate(List.of())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("현재 없는 항목을 보내면 AGREEMENT_VERSION_OUTDATED")
	void unknownItemIsOutdated() {
		List<AgreementAnswer> answers = List.of(
			new AgreementAnswer("TERMS", V, true),
			new AgreementAnswer("ADULT", V, true),
			new AgreementAnswer("REMOVED", V, true));

		assertErrorCode(() -> service.validate(answers), ErrorCode.AGREEMENT_VERSION_OUTDATED);
	}

	@Test
	@DisplayName("같은 항목을 두 번 보내면 INVALID_REQUEST")
	void duplicatedItemIsInvalid() {
		List<AgreementAnswer> answers = List.of(
			new AgreementAnswer("TERMS", V, true),
			new AgreementAnswer("TERMS", V, true),
			new AgreementAnswer("ADULT", V, true));

		assertErrorCode(() -> service.validate(answers), ErrorCode.INVALID_REQUEST);
	}

	@Test
	@DisplayName("처음 보내면 동의·거절을 모두 기록한다 (거절은 agreed = false)")
	void firstAgreementRecordsEveryAnswer() {
		given(repository.findByUserIdOrderByIdAsc(1L)).willReturn(List.of());

		service.agree(1L, answers(true, true, false));

		ArgumentCaptor<UserConsent> saved = ArgumentCaptor.forClass(UserConsent.class);
		verify(repository, times(3)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(c -> c.getConsentType() + ":" + c.isAgreed())
			.containsExactly("TERMS:true", "ADULT:true", "MARKETING:false");
	}

	@Test
	@DisplayName("현재 상태와 같은 내용을 다시 보내면 기록하지 않는다")
	void unchangedAnswersAreNotRecordedAgain() {
		given(repository.findByUserIdOrderByIdAsc(1L)).willReturn(List.of(
			consent("TERMS", V, true), consent("ADULT", V, true), consent("MARKETING", V, false)));

		service.agree(1L, answers(true, true, false));

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("동의했던 선택 항목을 false 로 보내면 철회(agreed = false)로 기록한다")
	void decliningOptionalItemRecordsWithdrawal() {
		given(repository.findByUserIdOrderByIdAsc(1L)).willReturn(List.of(
			consent("TERMS", V, true), consent("ADULT", V, true), consent("MARKETING", V, true)));

		service.agree(1L, answers(true, true, false));

		ArgumentCaptor<UserConsent> saved = ArgumentCaptor.forClass(UserConsent.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getConsentType()).isEqualTo("MARKETING");
		assertThat(saved.getValue().isAgreed()).isFalse();
		assertThat(service.hasActiveConsent(1L, "TERMS")).isTrue();
	}

	@Test
	@DisplayName("필수 항목마다 가장 최근 기록이 현재 버전 동의인지 확인한다. 선택 항목은 보지 않는다")
	void hasAgreedAllRequiredUsesLatestRecordPerType() {
		given(repository.findByUserIdOrderByIdAsc(1L)).willReturn(List.of(
			consent("TERMS", V, true), consent("ADULT", V, true), consent("MARKETING", V, false)));
		given(repository.findByUserIdOrderByIdAsc(2L)).willReturn(List.of(
			consent("TERMS", "old", true), consent("ADULT", V, true)));
		given(repository.findByUserIdOrderByIdAsc(3L)).willReturn(List.of(
			consent("TERMS", V, true), consent("ADULT", V, true), consent("ADULT", V, false)));

		assertThat(service.hasAgreedAllRequired(1L)).isTrue();
		assertThat(service.hasAgreedAllRequired(2L)).isFalse();
		assertThat(service.hasAgreedAllRequired(3L)).isFalse();
	}

	private static UserConsent consent(String type, String version, boolean agreed) {
		return new UserConsent(1L, type, version, agreed, NOW);
	}

	private static List<AgreementAnswer> answers(boolean terms, boolean adult, boolean marketing) {
		return List.of(
			new AgreementAnswer("TERMS", V, terms),
			new AgreementAnswer("ADULT", V, adult),
			new AgreementAnswer("MARKETING", V, marketing));
	}

	private static void assertErrorCode(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run)
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(expected);
	}
}

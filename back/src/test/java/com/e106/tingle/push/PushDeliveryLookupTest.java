package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

class PushDeliveryLookupTest {

	private static final long USER_ID = 41;
	private static final long INTERNAL_ID = 7;
	private static final Instant LINKED_AT = Instant.parse("2026-10-28T00:00:00Z");
	private static final UUID INSTALLATION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID BINDING_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private static final UUID FAMILY_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
	private static final String CIPHERTEXT = "synthetic-ciphertext";

	private final PushInstallationRepository installations = mock(PushInstallationRepository.class);
	private final RefreshTokenRepository sessions = mock(RefreshTokenRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final PushTokenEncryption encryption = mock(PushTokenEncryption.class);
	private final PushInstallation installation = mock(PushInstallation.class);
	private final PushDeliveryLookup lookup = new PushDeliveryLookup(installations, sessions, users,
		encryption, Clock.fixed(LINKED_AT, ZoneOffset.UTC));

	@BeforeEach
	void prepareCurrentInstallation() {
		User user = mock(User.class);
		when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		when(installations.findByUserIdOrderById(USER_ID)).thenReturn(List.of(installation));
		when(installations.findById(INTERNAL_ID)).thenReturn(Optional.of(installation));
		when(installation.getId()).thenReturn(INTERNAL_ID);
		when(installation.getUserId()).thenReturn(USER_ID);
		when(installation.getLoginFamilyId()).thenReturn(FAMILY_ID);
		when(sessions.isPushSessionUsable(FAMILY_ID, USER_ID, LINKED_AT)).thenReturn(true);
		when(installation.getClientInstallationId()).thenReturn(INSTALLATION_ID);
		when(installation.getPlatform()).thenReturn(PushPlatform.ANDROID);
		when(installation.getTokenCiphertext()).thenReturn(CIPHERTEXT);
		when(installation.getBindingId()).thenReturn(BINDING_ID);
		when(installation.getLinkedAt()).thenReturn(LINKED_AT);
	}

	@Test
	@DisplayName("현재 소유자·암호문·연결 세대가 같은 후보만 발송 직전에 해석한다")
	void resolvesUnchangedCurrentBinding() {
		PushDeliveryLookup.Candidate candidate = queuedCandidate();
		when(encryption.decrypt(INSTALLATION_ID, USER_ID, CIPHERTEXT)).thenReturn("synthetic-token");
		PushDeliveryTarget target = lookup.resolve(candidate).orElseThrow();
		assertThat(target.installationId()).isEqualTo(INSTALLATION_ID);
		assertThat(target.token()).isEqualTo("synthetic-token");
		verify(encryption).decrypt(INSTALLATION_ID, USER_ID, CIPHERTEXT);
	}

	@Test
	@DisplayName("토큰 암호문이 같아도 연결 UUID가 바뀐 이전 후보는 복호화하지 않는다")
	void rejectsChangedBindingWithoutRelyingOnTokenRotation() {
		PushDeliveryLookup.Candidate candidate = queuedCandidate();
		when(installation.getBindingId()).thenReturn(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
		assertThat(lookup.resolve(candidate)).isEmpty();
		verifyNoInteractions(encryption);
	}

	@Test
	@DisplayName("연결 시각이 바뀐 후보는 이전 예약의 수신 세대로 재사용하지 않는다")
	void rejectsChangedLinkTime() {
		PushDeliveryLookup.Candidate candidate = queuedCandidate();
		when(installation.getLinkedAt()).thenReturn(LINKED_AT.plusSeconds(1));
		assertThat(lookup.resolve(candidate)).isEmpty();
		verifyNoInteractions(encryption);
	}

	@Test
	@DisplayName("소유 연결이 비워진 설치의 오래된 후보는 예외나 발송 없이 거부한다")
	void rejectsClearedOwnerDuringResolve() {
		PushDeliveryLookup.Candidate candidate = queuedCandidate();
		when(installation.getUserId()).thenReturn(null);
		assertThat(lookup.resolve(candidate)).isEmpty();
		verifyNoInteractions(encryption);
	}

	@Test
	@DisplayName("소유 연결이 비워진 설치는 이전 공급자 오류로 삭제하지 않는다")
	void rejectsClearedOwnerDuringInvalidation() {
		PushDeliveryLookup.Candidate candidate = queuedCandidate();
		when(installation.getUserId()).thenReturn(null);
		assertThat(lookup.invalidate(candidate)).isFalse();
		verifyNoInteractions(encryption);
	}

	@Test
	@DisplayName("새 제안을 포함한 모든 알림은 폐기되거나 만료된 family에서 후보와 복호화를 차단한다")
	void rejectsEndedSessionForEveryNotificationKind() {
		for (PushNotificationKind kind : PushNotificationKind.values()) {
			when(sessions.isPushSessionUsable(FAMILY_ID, USER_ID, LINKED_AT)).thenReturn(true);
			PushDeliveryLookup.Candidate candidate = lookup.candidates(USER_ID, kind).getFirst();
			when(sessions.isPushSessionUsable(FAMILY_ID, USER_ID, LINKED_AT)).thenReturn(false);
			assertThat(lookup.candidates(USER_ID, kind)).isEmpty();
			assertThat(lookup.resolve(candidate)).isEmpty();
		}
		verifyNoInteractions(encryption);
	}

	@Test
	@DisplayName("family나 연결 세대 한쪽이 없는 legacy 설치를 제안 수신 대상으로 추정하지 않는다")
	void rejectsMissingSessionAndPartialGeneration() {
		when(installation.getLoginFamilyId()).thenReturn(null);
		assertThat(lookup.candidates(USER_ID, PushNotificationKind.NEW_PROPOSAL)).isEmpty();
		when(installation.getLoginFamilyId()).thenReturn(FAMILY_ID);
		when(installation.getBindingId()).thenReturn(null);
		assertThat(lookup.candidates(USER_ID, PushNotificationKind.NEW_PROPOSAL)).isEmpty();
		when(installation.getBindingId()).thenReturn(BINDING_ID);
		when(installation.getLinkedAt()).thenReturn(null);
		assertThat(lookup.candidates(USER_ID, PushNotificationKind.NEW_PROPOSAL)).isEmpty();
		verifyNoInteractions(encryption);
	}

	private PushDeliveryLookup.Candidate queuedCandidate() {
		return lookup.candidates(USER_ID, PushNotificationKind.NEW_PROPOSAL).getFirst();
	}
}

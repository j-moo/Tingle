package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchProposal.Decision;
import com.e106.tingle.proposal.MatchProposal.MatchingStatus;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;

/** 열람 권한만 검사하며 프로필·기기·현재 AI 선택 동의로 당시 판정 근거를 재계산하지 않는다. */
class ExplanationAccessTest {

	private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
	private final ProposalRepository proposals = mock(ProposalRepository.class);
	private final MatchingParticipantRepository participants = mock(MatchingParticipantRepository.class);
	private final MemberAccessRepository members = mock(MemberAccessRepository.class);
	private final ExplanationAccess access = new ExplanationAccess(proposals, participants, members,
		Clock.fixed(NOW, ZoneOffset.UTC));
	private MatchProposal proposal;
	private MatchingParticipant own;

	@BeforeEach
	void prepare() {
		proposal = MatchProposal.proposed(7, 8, NOW.minus(Duration.ofHours(1)));
		own = new MatchingParticipant(11, 7);
		given(proposals.findById(11L)).willReturn(Optional.of(proposal));
		given(participants.findById(new MatchingParticipant.Key(11L, 7L))).willReturn(Optional.of(own));
		given(members.canRespondToProposal(7)).willReturn(true);
		given(proposals.eligible(11)).willReturn(true);
	}

	@Test
	void invalidIdsAreRejectedBeforeAnyRepositoryAccess() {
		for (long invalid : List.of(0L, -1L)) {
			assertError(() -> access.require(invalid, 7), ErrorCode.INVALID_REQUEST);
			assertError(() -> access.require(11, invalid), ErrorCode.INVALID_REQUEST);
		}
		verifyNoInteractions(proposals, participants, members);
	}

	@Test
	void missingProposalIsHiddenWithoutCheckingMemberOrParticipant() {
		given(proposals.findById(11L)).willReturn(Optional.empty());
		assertError(() -> access.require(11, 7), ErrorCode.RESOURCE_NOT_FOUND);
		verifyNoInteractions(members, participants);
	}

	@Test
	void unproposedEncounterStatesRemainHidden() {
		for (MatchingStatus status : List.of(MatchingStatus.WAITING, MatchingStatus.NOT_MATCHED)) {
			ReflectionTestUtils.setField(proposal, "storageStatus", status);
			assertError(() -> access.require(11, 7), ErrorCode.RESOURCE_NOT_FOUND);
		}
		verifyNoInteractions(members, participants);
	}

	@Test
	void outsiderCannotDiscoverProposalState() {
		assertError(() -> access.require(11, 9), ErrorCode.RESOURCE_NOT_FOUND);
		verifyNoInteractions(members, participants);
	}

	@Test
	void failedCurrentMemberAccessStopsBeforeParticipantOrEligibilityRead() {
		given(members.canRespondToProposal(7)).willReturn(false);
		assertError(() -> access.require(11, 7), ErrorCode.ONBOARDING_REQUIRED);
		verifyNoInteractions(participants);
		verify(proposals, never()).eligible(11);
	}

	@Test
	void missingOwnParticipationIsStorageFailureRatherThanPermissionOrEmptyResult() {
		given(participants.findById(new MatchingParticipant.Key(11L, 7L))).willReturn(Optional.empty());
		assertError(() -> access.require(11, 7), ErrorCode.SERVICE_UNAVAILABLE);
		verify(proposals, never()).eligible(11);
	}

	@Test
	void pendingProposalReturnsTheSameStoredEvidenceForEitherParticipant() {
		given(members.canRespondToProposal(8)).willReturn(true);
		given(participants.findById(new MatchingParticipant.Key(11L, 8L)))
			.willReturn(Optional.of(new MatchingParticipant(11, 8)));
		assertThat(access.require(11, 7)).isSameAs(proposal);
		assertThat(access.require(11, 8)).isSameAs(proposal);
		verify(members).canRespondToProposal(7);
		verify(members).canRespondToProposal(8);
		verifyNoMoreInteractions(members);
		verify(participants, never()).findByMatchingId(11);
	}

	@Test
	void ownAcceptanceCanReadWhileWaitingForTheOtherParticipant() {
		own.decide(Decision.ACCEPT, UUID.randomUUID(), NOW.minusSeconds(30));
		proposal.recordResponses(Decision.ACCEPT, Decision.NONE);
		assertThat(access.require(11, 7)).isSameAs(proposal);
		verify(members).canRespondToProposal(7);
		verifyNoMoreInteractions(members);
	}

	@Test
	void earlierPartnerRejectionIsNotDisclosedBeforeOwnResponse() {
		proposal.recordResponses(Decision.NONE, Decision.DECLINE);
		assertThat(proposal.getStorageStatus()).isEqualTo(MatchingStatus.REJECTED);
		assertThat(access.require(11, 7)).isSameAs(proposal);
		verify(participants).findById(new MatchingParticipant.Key(11L, 7L));
		verifyNoMoreInteractions(participants);
	}

	@Test
	void ownRejectionAndMutualAcceptanceEndExplanationAccess() {
		own.decide(Decision.DECLINE, UUID.randomUUID(), NOW.minusSeconds(30));
		proposal.recordResponses(Decision.DECLINE, Decision.NONE);
		assertError(() -> access.require(11, 7), ErrorCode.PROPOSAL_EXPIRED);
		MatchingParticipant accepted = new MatchingParticipant(11, 7);
		accepted.decide(Decision.ACCEPT, UUID.randomUUID(), NOW.minusSeconds(30));
		given(participants.findById(new MatchingParticipant.Key(11L, 7L))).willReturn(Optional.of(accepted));
		proposal.recordResponses(Decision.ACCEPT, Decision.ACCEPT);
		assertError(() -> access.require(11, 7), ErrorCode.PROPOSAL_EXPIRED);
	}

	@Test
	void exactTwentyFourHourBoundaryExpiresEvenWithoutOwnResponse() {
		ReflectionTestUtils.setField(proposal, "proposedAt", NOW.minus(Duration.ofHours(24)).plusNanos(1000));
		assertThat(access.require(11, 7)).isSameAs(proposal);
		ReflectionTestUtils.setField(proposal, "proposedAt", NOW.minus(Duration.ofHours(24)));
		assertError(() -> access.require(11, 7), ErrorCode.PROPOSAL_EXPIRED);
	}

	@Test
	void closedStorageAndCurrentIneligibilityNeverLeakAnApparentlyPendingResult() {
		ReflectionTestUtils.setField(proposal, "storageStatus", MatchingStatus.CLOSED);
		assertError(() -> access.require(11, 7), ErrorCode.PROPOSAL_EXPIRED);
		ReflectionTestUtils.setField(proposal, "storageStatus", MatchingStatus.PENDING);
		given(proposals.eligible(11)).willReturn(false);
		assertError(() -> access.require(11, 7), ErrorCode.PROPOSAL_EXPIRED);
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode()).isEqualTo(expected);
	}
}

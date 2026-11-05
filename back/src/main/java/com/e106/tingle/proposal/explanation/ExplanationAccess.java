package com.e106.tingle.proposal.explanation;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;

/** 당시 평가 조회와 결과 반환 전에 같은 열람 범위를 검사한다. */
@Service
public class ExplanationAccess {

	private final ProposalRepository proposals;
	private final MatchingParticipantRepository participants;
	private final MemberAccessRepository members;
	private final Clock clock;

	public ExplanationAccess(ProposalRepository proposals, MatchingParticipantRepository participants,
		MemberAccessRepository members, Clock clock) {
		this.proposals = proposals;
		this.participants = participants;
		this.members = members;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public MatchProposal require(long id, long viewerId) {
		if (id <= 0 || viewerId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		MatchProposal proposal = proposals.findById(id)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		if (!proposal.isProposed() || (proposal.getUserLow() != viewerId && proposal.getUserHigh() != viewerId)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		if (!members.canRespondToProposal(viewerId)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		MatchingParticipant own = participants.findById(new MatchingParticipant.Key(id, viewerId))
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		MatchProposal.Status visible = proposal.visibleStatus(own.decision(), clock.instant());
		if ((visible != MatchProposal.Status.PENDING && visible != MatchProposal.Status.WAITING_FOR_OTHER)
			|| proposal.getStorageStatus() == MatchProposal.MatchingStatus.CLOSED || !proposals.eligible(id)) {
			throw new BusinessException(ErrorCode.PROPOSAL_EXPIRED);
		}
		return proposal;
	}
}

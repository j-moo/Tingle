package com.e106.tingle.proposal.dto;

import java.time.Instant;

public record ProposalView(Long proposalId, String status, String myDecision,
	Instant createdAt, Instant expiresAt, boolean chatAvailable, Long chatRoomId) {
}

package com.e106.tingle.proposal.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalPage(List<ProposalView> items, String nextCursor, Instant serverTime) {
}

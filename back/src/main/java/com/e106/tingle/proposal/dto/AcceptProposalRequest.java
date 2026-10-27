package com.e106.tingle.proposal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AcceptProposalRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String profileVersion) {
}

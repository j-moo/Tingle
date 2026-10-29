package com.e106.tingle.proposal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerProfileView(String nickname, int age,
	String gender, String introduction, String smokingStatus, String drinkingFrequency,
	String religionDisplay, boolean hasProfilePhoto, String profileVersion) {
}

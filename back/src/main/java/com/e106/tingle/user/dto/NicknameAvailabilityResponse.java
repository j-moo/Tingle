package com.e106.tingle.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NicknameAvailabilityResponse(String nickname, boolean available, String reason) {
}

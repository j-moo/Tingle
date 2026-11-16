package com.e106.tingle.user.dto;

/** 로그인할 때 받은 본인 연락 이메일. 없으면 null 로 내려간다. */
public record ContactEmailResponse(String email) {
}

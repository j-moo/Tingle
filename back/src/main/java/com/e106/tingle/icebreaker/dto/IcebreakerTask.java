package com.e106.tingle.icebreaker.dto;

public record IcebreakerTask(long chatRoomId, String kind, String status, String statusUrl,
	String errorCode, boolean retryable, int pollAfterSeconds) {
}

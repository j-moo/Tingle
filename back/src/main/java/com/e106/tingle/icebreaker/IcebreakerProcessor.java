package com.e106.tingle.icebreaker;

import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IcebreakerProcessor {
	private final IcebreakerStore store;
	private final IcebreakerGateway gateway;
	private final IcebreakerQuestions questions;

	public int runOnce() {
		int processed = 0;
		for (long id : store.due()) {
			if (process(id)) {
				processed++;
			}
		}
		return processed;
	}

	public boolean process(long roomId) {
		IcebreakerStore.Claim claim;
		try {
			claim = store.claim(roomId);
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
				return false;
			}
			throw exception;
		}
		if (claim == null) {
			return false;
		}
		IcebreakerGateway.Generated result = null;
		boolean retryable = false;
		try {
			result = claim.external()
				? gateway.generate(roomId, claim.attemptId(), claim.manifest(), claim.deadlineAt())
				: questions.fallback();
		} catch (IcebreakerGateway.GatewayException exception) {
			retryable = exception.retryable();
		} catch (RuntimeException exception) {
			retryable = false;
		}
		return store.complete(claim, result, retryable);
	}
}

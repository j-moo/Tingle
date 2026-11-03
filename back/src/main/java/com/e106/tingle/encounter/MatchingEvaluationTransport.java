package com.e106.tingle.encounter;

import java.util.UUID;

import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;

/** Transport is wired only after official runtime, immutable inputs and release approval are available. */
@FunctionalInterface
public interface MatchingEvaluationTransport {
	Evaluation evaluate(Request request, UUID idempotencyKey);
}

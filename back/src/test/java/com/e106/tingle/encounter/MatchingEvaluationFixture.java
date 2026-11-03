package com.e106.tingle.encounter;

import java.util.UUID;

import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class MatchingEvaluationFixture {
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final Request REQUEST = new Request(1001, UUID.fromString("11111111-1111-4111-8111-111111111111"),
		UUID.fromString("22222222-2222-4222-8222-222222222222"),
		UUID.fromString("33333333-3333-4333-8333-333333333333"));
	private static final String RESPONSE = """
		{
			"encounterId": 1001,
			"leftSnapshotId": "11111111-1111-4111-8111-111111111111",
			"rightSnapshotId": "22222222-2222-4222-8222-222222222222",
			"manifest": {
			"releaseId": "33333333-3333-4333-8333-333333333333",
			"surveyVersion": "synthetic-survey",
			"wordingVersion": "synthetic-wording",
			"scoringVersion": "synthetic-scoring",
			"typeModelVersion": "synthetic-type",
			"analysisVersion": "synthetic-analysis",
			"typeReferenceVersion": "synthetic-reference",
			"matchingVersion": "synthetic-matching",
			"productPolicyVersion": "synthetic-policy",
			"engineVersion": "synthetic-engine",
			"contentHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
			},
			"directionAB": 0,
			"directionBA": 100,
			"coverageAB": 0,
			"coverageBA": 1,
			"harmonicScore": 0,
			"eligible": true,
			"excluded": [],
			"unresolved": [],
			"reasonKeys": ["synthetic-only"]
		}
		""";

	private MatchingEvaluationFixture() {
	}

	static ObjectNode response() {
		return (ObjectNode)JSON.readTree(RESPONSE);
	}
}

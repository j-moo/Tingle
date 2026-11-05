package com.e106.keyring.lab;

import java.time.Instant;

import org.json.JSONException;
import org.json.JSONObject;

import com.e106.keyring.core.LocalEvidence;
import com.e106.keyring.core.SavedEncounter;

/** Export the original per-device values; never construct a proof from today's settings. */
public final class ContractJson {
	private ContractJson() {
	}

	public static JSONObject submission(SavedEncounter record) throws JSONException {
		if (!record.encounter.isContract() || record.evidence == null
			|| record.phase != SavedEncounter.Phase.CONFIRMED) {
			throw new IllegalArgumentException("NO_CONFIRMED_CONTRACT_SUBMISSION");
		}
		JSONObject value = new JSONObject();
		value.put("submissionId", record.submissionId.toString());
		value.put("eventId", record.encounter.eventId.toString());
		value.put("occurredAt", Instant.ofEpochMilli(record.encounter.occurredAt).toString());
		value.put("policyVersion", record.encounter.profileA.policyVersion);
		value.put("matchingRuleVersion", record.encounter.profileA.ruleVersion);
		value.put("settingsSnapshotId", record.evidence.snapshotId.toString());
		value.put("peerCredential", record.evidence.peerCredential);
		value.put("evidence", new JSONObject().put("protocolVersion", LocalEvidence.PROTOCOL)
			.put("payload", record.evidence.payload));
		return value;
	}
}

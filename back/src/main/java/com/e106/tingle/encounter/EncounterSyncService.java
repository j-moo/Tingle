package com.e106.tingle.encounter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.dto.EncounterSyncResponse;
import com.e106.tingle.encounter.dto.EncounterSyncResponse.Receipt;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 최초 저장 영수증을 복구하며, 공식 만남 증명과 당시 입력 대응 없이 신규 저장 성공을 만들지 않는다. */
@Service
@RequiredArgsConstructor
public class EncounterSyncService {
	private final EncounterAccessRepository access;
	private final EncounterReceipts receipts;
	private final JsonMapper mapper;

	@Transactional
	public EncounterSyncResponse sync(long userId, EncounterSyncRequest request) {
		access.requireActiveMember(userId);
		access.requireOwnedRegisteredDevice(userId, request.deviceId());
		return new EncounterSyncResponse(request.submissions().stream()
			.map(submission -> replayOrUnresolved(userId, request.deviceId(), submission)).toList());
	}

	private Receipt replayOrUnresolved(long userId, long deviceId, Submission submission) {
		String requestHash = receipts.canonicalHash(submission);
		return receipts.find(userId, deviceId, submission.submissionId(), submission.eventId(), requestHash)
			.map(saved -> mapper.readValue(saved.responseJson(), Receipt.class))
			.orElseGet(() -> Receipt.unresolved(submission));
	}
}

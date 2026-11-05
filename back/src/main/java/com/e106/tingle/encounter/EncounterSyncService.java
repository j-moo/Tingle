package com.e106.tingle.encounter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.dto.EncounterSyncResponse;
import com.e106.tingle.encounter.dto.EncounterSyncResponse.Receipt;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

/** 공식 접수기를 명시 구성한 경우만 신규 저장하며 공급자가 없으면 기존 복구·미저장 계약을 유지한다. */
@Service
public class EncounterSyncService {
	private final EncounterAccessRepository access;
	private final EncounterReceipts receipts;
	private final JsonMapper mapper;
	private final ObjectProvider<VerifiedEncounterIntake> intakes;
	private final TransactionTemplate recovery;

	@Autowired
	public EncounterSyncService(EncounterAccessRepository access, EncounterReceipts receipts, JsonMapper mapper,
		ObjectProvider<VerifiedEncounterIntake> intakes, PlatformTransactionManager transactions) {
		this.access = access;
		this.receipts = receipts;
		this.mapper = mapper;
		this.intakes = intakes;
		recovery = transactions == null ? null : new TransactionTemplate(transactions);
	}

	/** 기존 내부 복구 호출의 생성 계약을 보존한다. 해당 호출자는 기존과 같이 트랜잭션을 소유한다. */
	public EncounterSyncService(EncounterAccessRepository access, EncounterReceipts receipts, JsonMapper mapper) {
		this(access, receipts, mapper, null, null);
	}

	public EncounterSyncResponse sync(long userId, EncounterSyncRequest request) {
		VerifiedEncounterIntake intake = intakes == null ? null : intakes.getIfAvailable();
		if (intake != null) {
			return new EncounterSyncResponse(request.submissions().stream()
				.map(submission -> accept(intake, userId, request.deviceId(), submission)).toList());
		}
		return recovery == null ? recover(userId, request)
			: recovery.execute(status -> recover(userId, request));
	}

	private Receipt accept(VerifiedEncounterIntake intake, long user, long device, Submission submission) {
		// 기존 영수증 복구는 공식 proof 공급자 재가동을 기다리지 않는다. 잠금은 신규 정렬 잠금 전에 해제한다.
		Receipt saved = recovery.execute(status -> {
			access.requireActiveMember(user);
			access.requireOwnedRegisteredDevice(user, device);
			String hash = receipts.canonicalHash(submission);
			return receipts.find(user, device, submission.submissionId(), submission.eventId(), hash)
				.map(receipt -> mapper.readValue(receipt.responseJson(), Receipt.class)).orElse(null);
		});
		if (saved != null) {
			return saved;
		}
		try {
			return intake.accept(user, device, submission);
		} catch (BusinessException exception) {
			if (exception.getErrorCode() != ErrorCode.POLICY_UNRESOLVED) {
				throw exception;
			}
			return Receipt.unresolved(submission);
		}
	}

	private EncounterSyncResponse recover(long userId, EncounterSyncRequest request) {
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

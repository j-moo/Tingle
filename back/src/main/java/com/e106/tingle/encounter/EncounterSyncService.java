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
import com.e106.tingle.encounter.dto.EncounterSyncResponse.ServerReceipt;
import com.e106.tingle.encounter.peer.Ter2BusinessPromotion;
import com.e106.tingle.encounter.peer.Ter2ReceiptService;
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
	private final ObjectProvider<Ter2ReceiptService> peerIntakes;
	private final ObjectProvider<Ter2BusinessPromotion> promotions;
	private final TransactionTemplate recovery;

	@Autowired
	public EncounterSyncService(EncounterAccessRepository access, EncounterReceipts receipts, JsonMapper mapper,
		ObjectProvider<VerifiedEncounterIntake> intakes, PlatformTransactionManager transactions,
		ObjectProvider<Ter2ReceiptService> peerIntakes, ObjectProvider<Ter2BusinessPromotion> promotions) {
		this.access = access;
		this.receipts = receipts;
		this.mapper = mapper;
		this.intakes = intakes;
		this.peerIntakes = peerIntakes;
		this.promotions = promotions;
		recovery = transactions == null ? null : new TransactionTemplate(transactions);
	}

	public EncounterSyncService(EncounterAccessRepository access, EncounterReceipts receipts, JsonMapper mapper,
		ObjectProvider<VerifiedEncounterIntake> intakes, PlatformTransactionManager transactions,
		ObjectProvider<Ter2ReceiptService> peerIntakes) {
		this(access, receipts, mapper, intakes, transactions, peerIntakes, null);
	}

	public EncounterSyncService(EncounterAccessRepository access, EncounterReceipts receipts, JsonMapper mapper,
		ObjectProvider<VerifiedEncounterIntake> intakes, PlatformTransactionManager transactions) {
		this(access, receipts, mapper, intakes, transactions, null);
	}

	/** 기존 내부 복구 호출의 생성 계약을 보존한다. 해당 호출자는 기존과 같이 트랜잭션을 소유한다. */
	public EncounterSyncService(EncounterAccessRepository access, EncounterReceipts receipts, JsonMapper mapper) {
		this(access, receipts, mapper, null, null);
	}

	public EncounterSyncResponse sync(long userId, EncounterSyncRequest request) {
		Ter2ReceiptService peer = peerIntakes == null ? null : peerIntakes.getIfAvailable();
		if (peer != null && request.submissions().stream().anyMatch(EncounterSyncService::isTer2)) {
			return new EncounterSyncResponse(request.submissions().stream().map(submission -> {
				if (!isTer2(submission)) {
					return sync(userId, new EncounterSyncRequest(request.deviceId(), java.util.List.of(submission)))
						.results().getFirst();
				}
				try {
					var accepted = peer.accept(userId, request.deviceId(), submission);
					String state = accepted.encounterStatus();
					String error = null;
					if ("VERIFIED_BOTH".equals(state)) {
						Ter2BusinessPromotion promotion = promotions == null ? null : promotions.getIfAvailable();
						try {
							if (promotion == null) {
								throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
							}
							promotion.promote(userId, request.deviceId(), submission);
							state = "BUSINESS_ENQUEUED";
						} catch (BusinessException exception) {
							// 일시 오류(5xx)·DB·트랜잭션 오류는 접수증으로 바꾸지 않는다. 접수증을 받으면 앱이 원본을 지워 승격을 다시 할 수 없다.
							// 요청 전체가 실패해야 앱이 원본을 지우지 않고 다시 보내고, 같은 제출은 기존 접수증을 복구한 뒤 승격을 다시 시도한다.
							if (exception.getErrorCode().getStatus().is5xxServerError()) {
								throw exception;
							}
							// 정책 미준비 등: 원본 접수증은 이미 커밋됐고 업무 준비 여부가 그것을 되돌리지 않는다.
							state = "BUSINESS_POLICY_PENDING";
							error = exception.getErrorCode().name();
						}
					}
					return new Receipt(accepted.submissionId(), accepted.eventId(), "STORED", accepted.receiptId(),
						true, state, accepted.expiresAt(), false, error,
						mapper.convertValue(accepted.proof(), ServerReceipt.class));
				} catch (BusinessException exception) {
					if (exception.getErrorCode() != ErrorCode.POLICY_UNRESOLVED) {
						throw exception;
					}
					return Receipt.unresolved(submission);
				}
			}).toList());
		}
		VerifiedEncounterIntake intake = intakes == null ? null : intakes.getIfAvailable();
		if (intake != null) {
			return new EncounterSyncResponse(request.submissions().stream()
				.map(submission -> accept(intake, userId, request.deviceId(), submission)).toList());
		}
		return recovery == null ? recover(userId, request)
			: recovery.execute(status -> recover(userId, request));
	}

	private static boolean isTer2(Submission submission) {
		return submission.evidence() != null
			&& "tingle-encounter-v2".equals(submission.evidence().protocolVersion());
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

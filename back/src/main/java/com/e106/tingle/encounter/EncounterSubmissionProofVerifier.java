package com.e106.tingle.encounter;

import java.time.Instant;
import java.util.UUID;

import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;

/**
 * 공식 만남 서명·peerCredential·신뢰키·당시 소유 구간과 양측 이벤트/시각을 검증하는 공급 경계.
 * REGISTER/STORED 증명이나 Lab 증명으로 대체하지 않는다. 승인된 규격이 없으면 구현을 등록하지 않는다.
 * 네트워크를 호출하지 않는다. 입력은 변경하지 않고 검증한 원문에서만 claims를 반환한다. 형식 확인만으로 성공을 반환하지 않는다.
 */
@FunctionalInterface
public interface EncounterSubmissionProofVerifier {
	Claims verify(long userId, long deviceId, Submission submission);

	record Claims(long userId, long deviceId, long peerUserId, long peerDeviceId, UUID submissionId,
		UUID eventId, Instant occurredAt, String policyVersion, String matchingRuleVersion,
		UUID settingsSnapshotId, UUID ownerGeneration, long settingsVersion, String inputHash, String proofHash,
		Long ownershipEpoch, UUID peerOwnerGeneration, Long peerOwnershipEpoch, Instant expiresAt,
		Long opportunitySeconds) {
		/** Legacy producers have no signed ownership interval or opportunity fields. */
		public Claims(long userId, long deviceId, long peerUserId, long peerDeviceId, UUID submissionId,
			UUID eventId, Instant occurredAt, String policyVersion, String matchingRuleVersion,
			UUID settingsSnapshotId, UUID ownerGeneration, long settingsVersion, String inputHash, String proofHash) {
			this(userId, deviceId, peerUserId, peerDeviceId, submissionId, eventId, occurredAt, policyVersion,
				matchingRuleVersion, settingsSnapshotId, ownerGeneration, settingsVersion, inputHash, proofHash,
				null, null, null, null, null);
		}
	}
}

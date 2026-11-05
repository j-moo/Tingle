package com.ssafy.e106.features.keyring

import com.ssafy.e106.features.keyring.ui.*
import kotlin.test.*

/** 검토용 화면의 파괴적 선택/완료 표시 경계만 검사한다. 서버·BLE 초기화 검증은 아니다. */
class KeyringResetPresentationTest {
    private val confirmation = KeyringResetState(stage = KeyringResetStage.Confirm,
        deviceLabel = "예시 키링", ownershipConfirmed = true, connected = true, accepted = true)

    @Test fun unreadRecordsNeverCountAsEmpty() {
        assertFalse(confirmation.canConfirm)
        assertFalse(confirmation.copy(pendingRecordCount = -1).canConfirm)
        assertTrue(confirmation.copy(pendingRecordCount = 0).canConfirm)
    }

    @Test fun deletingUnsentRecordsRequiresBothIndependentConsents() {
        val deleting = confirmation.copy(pendingRecordCount = 3, recordChoice = ResetRecordChoice.Delete)
        assertFalse(deleting.canConfirm)
        assertTrue(deleting.copy(recordDeletionAccepted = true).canConfirm)
        assertFalse(deleting.copy(accepted = false, recordDeletionAccepted = true).canConfirm)
    }

    @Test fun uploadChoiceDoesNotAuthorizeResetEvenWithStaleDeletionConsent() {
        assertFalse(confirmation.copy(pendingRecordCount = 3, recordChoice = ResetRecordChoice.UploadFirst,
            recordDeletionAccepted = true).canConfirm)
    }

    @Test fun losingOwnershipOrConnectionBlocksPreviouslyAcceptedReset() {
        val ready = confirmation.copy(pendingRecordCount = 0)
        assertFalse(ready.copy(ownershipConfirmed = false).canConfirm)
        assertFalse(ready.copy(connected = false).canConfirm)
    }

    @Test fun partialOrTimeoutNeverBecomesCompleteWithoutBothConfirmations() {
        val complete = confirmation.copy(stage = KeyringResetStage.Complete)
        assertEquals(KeyringResetStage.Unknown, complete.copy(deviceResetConfirmed = true).displayStage)
        assertEquals(KeyringResetStage.Unknown, complete.copy(ownershipReleased = true).displayStage)
        assertEquals(KeyringResetStage.Complete, complete.copy(deviceResetConfirmed = true, ownershipReleased = true).displayStage)
        assertEquals(KeyringResetStage.Unknown, confirmation.copy(stage = KeyringResetStage.Partial).displayStage)
        assertFalse(confirmation.copy(stage = KeyringResetStage.Unknown).canConfirm)
    }
}

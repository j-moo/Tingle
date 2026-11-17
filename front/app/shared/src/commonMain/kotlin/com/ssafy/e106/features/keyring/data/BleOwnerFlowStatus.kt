package com.ssafy.e106.features.keyring.data

import kotlinx.serialization.json.*

/** 0.7 지원 선언이다. 발급 형식 선택에 사용하며 서버 소유 증명·보호 연결 성공과 구분한다. */
data class BleOwnerFlowInfo(
    val keyId: String? = null,
    val peerProtocol: String? = null,
    val peerOwnerScopeVersion: Long? = null,
    val accountLockProofVersion: String? = null,
) {
    /** 펌웨어 이름 대신 TSV3 형식·보호 특성·peer 소유 세대 선언을 모두 대조한다. */
    fun supportsFullSettings(info: BleDeviceInfo): Boolean =
        FULL_SETTINGS_FORMAT in info.profileStorage.supportedFormats.orEmpty() &&
        info.profileStorage.characteristic?.lowercase() == BleProfileStorageInfo.CHARACTERISTIC &&
        info.profileStorage.requiresEncryption == true &&
        info.supportedFunctions.containsAll(listOf("GET_SYNC_CHALLENGE", "SYNC_SETTINGS")) &&
        info.supportsProof(KeyringRegistrationProtocol.VERSION_2) &&
        peerProtocol == "tingle-peer-v3" && peerOwnerScopeVersion == 1L &&
        accountLockProofVersion == "tingle-account-lock-v2"

    companion object { const val FULL_SETTINGS_FORMAT = "tingle-keyring-settings-v3" }
}

/** READ 인증된 응답에만 담기는 집계. 미측정/손상 값은 null이며 사용률로 FULL을 추정하지 않는다. */
data class BleRecordStorage(
    val totalBytes: Long?, val usedBytes: Long?, val state: String?,
    val candidateCount: Long?, val reviewCount: Long?, val unresolvedAccountLockCount: Long?,
)

/** 기기 관측 상태와 서버 ACK는 별개다. 재연결/계정 변경 시 기존 BleProbeState와 함께 폐기된다. */
data class BleOwnerFlowStatus(
    val ownerAuthorized: Boolean? = null,
    val records: BleRecordStorage? = null,
    val cursorEpoch: String? = null,
    val applied: Boolean? = null,
    val timeReady: Boolean? = null,
    val matchingReady: Boolean? = null,
    val storedSettingsVersion: Long? = null,
    val batteryPercentSource: String? = null,
    val batteryPresence: String? = null,
    val powerSource: String? = null,
    // 생략은 기존 동작(복구 불필요)이다. true일 때만 같은 버전이라도 새 서명 동기화가 필요하다.
    val settingsRecoveryRequired: Boolean? = null,
    // DEVICE_INFO 전용. 기록 저장소 오류에서는 설정을 적용해도 applied=false가 유지된다.
    val storageHealthy: Boolean? = null,
    // 격리 기록이 남은 보드. storageHealthy=false지만 동기화 뒤에는 설정을 정상 적용한다.
    val recordsRecoveryRequired: Boolean? = null,
) {
    // 모순된 보고를 준비 완료로 합성하지 않고 사용자가 다시 조회할 수 있게 알린다.
    val readinessConflict: Boolean get() = matchingReady == true &&
        (applied == false || timeReady == false || records?.unresolvedAccountLockCount?.let { it > 0 } == true)
}

/** 사용자에게 보이는 매칭 준비 상태. 현재 연결에서 읽지 못했거나 값이 없으면 Unknown이며 준비로 보지 않는다. */
enum class KeyringMatchReadiness { Ready, NotApplied, MatchingOff, SignupPending, Restricted, StorageError, Unknown }

/** 재연결 때 현재 보드에서 새로 읽은 DEVICE_INFO로 재전송 여부와 준비 상태를 정한다. */
internal object KeyringBoardSync {
    /** 저장 버전·해시·소유 순번이 같아도 재부팅 뒤 미적용·시간 미확보·설정 복구면 새 challenge로 다시 보낸다.
     * matchingReady=false는 매칭 OFF·가입 전에도 정상이므로 재전송 조건이 아니다. */
    fun needsResync(info: BleDeviceInfo, run: KeyringSyncRun): Boolean {
        val board = info.readiness
        if ((run.ownershipEpoch != null && info.ownershipEpoch != run.ownershipEpoch) ||
            info.ownerFlow.keyId?.let { it != run.keyId } == true ||
            info.storedSettingsVersion != run.settingsVersion || info.storedPayloadHash != run.payloadHash ||
            board.settingsRecoveryRequired == true) return true
        // 저장소 오류 보드는 다시 보내도 applied=false로 남는다. 이번 부팅에서 이미 서명 시간을 받았으면(재전송 뒤)
        // 같은 설정을 반복하지 않는다. 격리 기록만 있는 보드는 동기화로 적용되므로 막지 않는다.
        if (board.storageHealthy == false && board.timeReady == true && board.recordsRecoveryRequired != true) return false
        // 이전 ON/OFF·196B 형식은 두 플래그를 적용 판단에 쓰지 않는다(시험 빌드는 항상 false). TSV3만 대조한다.
        return run.payloadFormat == BleOwnerFlowInfo.FULL_SETTINGS_FORMAT && (board.applied != true || board.timeReady != true)
    }

    /** matchingReady=false는 사용자의 OFF 선택, 가입 미완료, 그 밖의 보드 제한을 구분한다. 서버 설정을 모르면 Unknown이다. */
    fun readiness(board: BleOwnerFlowStatus?, matchingEnabled: Boolean?, signupPending: Boolean): KeyringMatchReadiness = when {
        board == null || board.applied == null || board.timeReady == null || board.matchingReady == null ||
            board.readinessConflict -> KeyringMatchReadiness.Unknown
        board.applied == false && board.storageHealthy == false && board.settingsRecoveryRequired != true ->
            KeyringMatchReadiness.StorageError
        board.applied == false || board.timeReady == false || board.settingsRecoveryRequired == true -> KeyringMatchReadiness.NotApplied
        board.matchingReady == true -> KeyringMatchReadiness.Ready
        matchingEnabled == false -> KeyringMatchReadiness.MatchingOff
        signupPending -> KeyringMatchReadiness.SignupPending
        matchingEnabled == true -> KeyringMatchReadiness.Restricted
        else -> KeyringMatchReadiness.Unknown
    }
}

/** 신형 응답의 확정 기록과 검토용 기록 모두 전송 대상이다. 후보나 일부 집계만으로 총량을 추정하지 않는다. */
internal val BleDeviceStatus.unsentCount: Long?
    get() {
        val pending = pendingRecordCount ?: return null
        if (ownerFlow.ownerAuthorized == null) return pending
        val records = ownerFlow.records ?: return null
        if (records.state !in setOf("AVAILABLE", "FULL")) return null
        val review = records.reviewCount ?: return null
        return if (pending <= Long.MAX_VALUE - review) pending + review else null
    }

/** 신형 필드가 없거나 타입/범위가 맞지 않으면 미확인으로 남긴다. 문자열 숫자·boolean을 자동 변환하지 않는다. */
internal object BleOwnerFlowProtocol {
    fun flag(value: JsonObject, key: String): Boolean? =
        (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun number(value: JsonObject, key: String): Long? =
        (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
    private fun text(value: JsonObject, key: String): String? =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length in 1..100 }
    private fun choice(value: JsonObject, key: String, allowed: Set<String>): String? = text(value, key)?.takeIf { it in allowed }

    fun info(value: JsonObject) = BleOwnerFlowInfo(
        text(value, "keyId")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) },
        text(value, "peerExchangeProtocol"), number(value, "peerOwnerScopeVersion"), text(value, "accountLockProofVersion"),
    )

    fun status(value: JsonObject): BleOwnerFlowStatus {
        val authorized = flag(value, "ownerAuthorized")
        var total = number(value, "storageTotalBytes")?.takeIf { it > 0 }
        var used = number(value, "storageUsedBytes")
        // 두 값이 모순되면 공간 정보를 만들지 않는다. 별도 저장 상태는 펌웨어 보고 그대로 유지한다.
        if (total != null && used != null && used > total) { total = null; used = null }
        val records = if (authorized == true) BleRecordStorage(total, used,
            choice(value, "storageState", setOf("AVAILABLE", "FULL", "ERROR", "UNKNOWN")),
            number(value, "candidateCount"), number(value, "reviewRecordCount"), number(value, "unresolvedAccountLockCount")) else null
        return BleOwnerFlowStatus(authorized, records,
            text(value, "recordCursorEpoch")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) },
            flag(value, "applied"), flag(value, "timeReady"), flag(value, "matchingReady"), number(value, "storedSettingsVersion"),
            choice(value, "batteryPercentSource", setOf("VOLTAGE_ESTIMATE", "FUEL_GAUGE", "UNKNOWN")),
            choice(value, "batteryPresence", setOf("PRESENT", "ABSENT", "UNCONFIRMED")),
            choice(value, "powerSource", setOf("USB", "USB_ABSENT", "UNKNOWN")), flag(value, "settingsRecoveryRequired"),
            flag(value, "storageHealthy"), flag(value, "recordsRecoveryRequired"))
    }
}

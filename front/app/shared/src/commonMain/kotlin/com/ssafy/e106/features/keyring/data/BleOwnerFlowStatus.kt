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
) {
    // 모순된 보고를 준비 완료로 합성하지 않고 사용자가 다시 조회할 수 있게 알린다.
    val readinessConflict: Boolean get() = matchingReady == true &&
        (applied == false || timeReady == false || records?.unresolvedAccountLockCount?.let { it > 0 } == true)
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
            choice(value, "powerSource", setOf("USB", "USB_ABSENT", "UNKNOWN")))
    }
}

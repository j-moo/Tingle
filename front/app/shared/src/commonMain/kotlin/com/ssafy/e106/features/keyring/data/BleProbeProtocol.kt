package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.ble.*
import kotlinx.serialization.json.*

/** 공개 읽기 명령만 연결 화면에서 쓴다. 등록 증명·설정 변경·초기화는 별도 업무 계약에서 연결한다. */
internal object BleProbeProtocol {
    // 전체 STATUS는 기존 512바이트보다 크다. IoT 0.7 계약의 상한을 적용한다.
    const val STATUS_RESPONSE_MAX_BYTES = 2304

    fun request(type: String, id: String): ByteArray {
        require(type in setOf("GET_DEVICE_INFO", "GET_STATUS") && id.encodeToByteArray().size in 1..36)
        return (buildJsonObject { put("protocolVersion", 1); put("type", type); put("requestId", id) }.toString() + "\n").encodeToByteArray()
    }

    fun response(bytes: ByteArray, id: String, type: String, alternateType: String? = null): JsonObject {
        val value = try { Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject }
        catch (_: Exception) { throw BleException(BleFailure.InvalidFrame) }
        if (value["protocolVersion"] != JsonPrimitive(1) || value["requestId"] != JsonPrimitive(id))
            throw BleException(BleFailure.InvalidFrame)
        if (value["type"] == JsonPrimitive("ERROR")) {
            val code = value.string("code")?.take(80) ?: "UNKNOWN"
            // 구형 증명 누락만 건너뛸 수 있다. 그 외 오류의 위치로 기록을 건너뛰지 않는다.
            val sequence = number(value, "sequence")?.takeIf {
                code == "LEGACY_EVIDENCE_UNAVAILABLE" && it in 1..UInt.MAX_VALUE.toLong()
            }
            throw BleCommandException(code, sequence)
        }
        // 기록 조회는 같은 요청에 RECORD 또는 RECORD_END를 반환한다. 그 외 호출의 단일 타입 검사는 유지한다.
        if (value["type"] != JsonPrimitive(type) && (alternateType == null || value["type"] != JsonPrimitive(alternateType)))
            throw BleException(BleFailure.InvalidFrame)
        return value
    }

    fun info(value: JsonObject): BleDeviceInfo {
        val serial = value.string("deviceSerial") ?: throw BleException(BleFailure.InvalidFrame)
        val version = value.string("firmwareVersion") ?: throw BleException(BleFailure.InvalidFrame)
        if (!serial.matches(Regex("[A-Za-z0-9._-]{1,64}")) || version.length !in 1..80) throw BleException(BleFailure.InvalidFrame)
        val functions = (value["supportedFunctions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        // 구형 펌웨어의 생략과 신형 필드 손상을 구분한다. 미확인 epoch를 0으로 만들지 않는다.
        val epoch = value["ownershipEpoch"]?.let {
            number(value, "ownershipEpoch")?.takeIf { n -> n >= 0 } ?: throw BleException(BleFailure.InvalidFrame)
        }
        val owner = value["ownerState"]?.let {
            value.string("ownerState")?.takeIf { s -> s in setOf("BOUND", "UNOWNED", "RESETTING", "UNKNOWN") }
                ?: throw BleException(BleFailure.InvalidFrame)
        }
        val versions = value["supportedProofProtocolVersions"]?.let {
            (it as? JsonArray)?.map { p ->
                (p as? JsonPrimitive)?.takeIf { s -> s.isString }?.content ?: throw BleException(BleFailure.InvalidFrame)
            } ?: throw BleException(BleFailure.InvalidFrame)
        } ?: listOf(KeyringRegistrationProtocol.VERSION)
        return BleDeviceInfo(serial, version, "GET_STATUS" in functions, "GET_REGISTRATION_PROOF" in functions,
            epoch, owner, versions, value.string("resetContractVersion"), functions, profileInfo(value), BleOwnerFlowProtocol.info(value),
            number(value, "storedSettingsVersion")?.takeIf { it > 0 },
            value.string("storedPayloadHash")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) })
    }

    /** 구형 필드 생략은 미확인이다. 문자열 true/숫자 문자열/잘못된 배열을 지원 성공으로 보정하지 않는다. */
    private fun profileInfo(value: JsonObject): BleProfileStorageInfo {
        fun text(key: String, emptyAllowed: Boolean = false): String? = value[key]?.let {
            value.string(key)?.takeIf { s -> s.length in (if (emptyAllowed) 0 else 1)..100 }
                ?: throw BleException(BleFailure.InvalidFrame)
        }
        fun flag(key: String): Boolean? = value[key]?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull
                ?: throw BleException(BleFailure.InvalidFrame)
        }
        val formats = value["supportedPayloadFormats"]?.let {
            val items = it as? JsonArray ?: throw BleException(BleFailure.InvalidFrame)
            if (items.size > 16) throw BleException(BleFailure.InvalidFrame)
            items.map { item -> (item as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?.takeIf { s -> s.length in 1..100 } ?: throw BleException(BleFailure.InvalidFrame) }
        }
        val bytes = value["storedPayloadBytes"]?.let {
            number(value, "storedPayloadBytes")?.takeIf { n -> n in 0..2048 }
                ?: throw BleException(BleFailure.InvalidFrame)
        }
        return BleProfileStorageInfo(formats, text("profileStorageCharacteristic"), flag("profileRequiresEncryption"),
            text("storedPayloadFormat", emptyAllowed = true), flag("profileStored"), bytes)
    }

    /** 공개 상태와 READ 인증 상세를 분리한다. 조회 결과는 서버 ACK/가입 완료의 근거가 아니다. */
    fun status(value: JsonObject): BleDeviceStatus {
        val authorized = BleOwnerFlowProtocol.flag(value, "ownerAuthorized")
        // 구형 공개 집계는 유지하되, 신형에서 인증 실패/손상된 응답의 기록 수를 노출하지 않는다.
        val canReadCount = !value.containsKey("ownerAuthorized") || authorized == true
        return BleDeviceStatus(
            number(value, "batteryPercent")?.takeIf { it in 0..100 },
            number(value, "batteryMillivolts")?.takeIf { it > 0 },
            BleOwnerFlowProtocol.flag(value, "charging"),
            if (canReadCount) number(value, "pendingRecordCount")?.takeIf { it >= 0 } else null,
            BleOwnerFlowProtocol.status(value),
        )
    }
    private fun number(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

class BleCommandException(val code: String, val legacySequence: Long? = null) : Exception("BLE command failed")
data class BleDeviceInfo(val serial: String, val firmware: String, val supportsStatus: Boolean, val supportsRegistration: Boolean = false,
    val ownershipEpoch: Long? = null, val ownerState: String? = null,
    val supportedProofProtocolVersions: List<String> = listOf("tingle-device-proof-v1"),
    val resetContractVersion: String? = null, val supportedFunctions: List<String> = emptyList(),
    val profileStorage: BleProfileStorageInfo = BleProfileStorageInfo(),
    val ownerFlow: BleOwnerFlowInfo = BleOwnerFlowInfo(),
    val storedSettingsVersion: Long? = null, val storedPayloadHash: String? = null) {
    /** 지원 선언은 암호화 성공/신원 증명이 아니다. 실제 전송 전 동일 연결의 보호 Read가 추가로 필요하다. */
    val supportsProtectedProfileStorage get() = profileStorage.supportsProtectedStorage &&
        supportedFunctions.containsAll(listOf("GET_SYNC_CHALLENGE", "SYNC_SETTINGS"))
    // 자동 전송과 수동 화면 모두 같은 기록 명령 지원 기준을 사용한다.
    internal val supportsEncounterTransfer get() = supportsStatus && supportedFunctions.containsAll(listOf(
        "GET_OWNER_CHALLENGE", "AUTHORIZE_OWNER", "GET_RECORD", "READ_RECORD_CHUNK", "ACK_RECORD",
    ))
    // 버전 이름만으로 삭제 명령 지원을 추측하지 않는다. 세 명령과 순번을 모두 확인해야 복구에 진입한다.
    internal val supportsReset get() = resetContractVersion == KeyringResetProtocol.VERSION &&
        ownershipEpoch != null && ownershipEpoch >= 0 && ownerState in setOf("BOUND", "UNOWNED", "RESETTING") &&
        supportedFunctions.containsAll(listOf("GET_RESET_CHALLENGE", "RESET_DEVICE", "GET_RESET_RESULT"))
    /** v2 명령은 펌웨어 이름이 아니라 실제 응답의 지원 규격·소유 순번으로 판단한다. */
    internal fun supportsProof(version: String) = version in supportedProofProtocolVersions &&
        ownerState !in setOf("RESETTING", "UNKNOWN") && when (version) {
            KeyringRegistrationProtocol.VERSION -> ownershipEpoch == null || ownershipEpoch == 0L
            KeyringRegistrationProtocol.VERSION_2 -> ownershipEpoch != null && ownershipEpoch >= 0 &&
                ownerState in setOf("UNOWNED", "BOUND")
            else -> false
        }
}

/** DEVICE_INFO 진단 값만 보관한다. 가입 상태·STORED 서명 검증·서버 ACK의 근거로 사용하지 않는다. */
data class BleProfileStorageInfo(
    val supportedFormats: List<String>? = null,
    val characteristic: String? = null,
    val requiresEncryption: Boolean? = null,
    val storedFormat: String? = null,
    val stored: Boolean? = null,
    val storedBytes: Long? = null,
) {
    internal val supportsProtectedStorage get() = FORMAT in supportedFormats.orEmpty() &&
        characteristic?.lowercase() == CHARACTERISTIC && requiresEncryption == true
    // true 하나만 도착하거나 형식/길이가 모순되면 저장됨으로 표시하지 않는다.
    val reportsProfileStored: Boolean? get() = when {
        stored == true && storedFormat == FORMAT && storedBytes == 196L -> true
        stored == false && storedFormat != null && storedFormat != FORMAT && storedBytes != null -> false
        else -> null
    }
    companion object {
        const val FORMAT = "tingle-profile-storage-v1"
        const val CHARACTERISTIC = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
    }
}
/** 같은 연결에서 마지막으로 받은 관측값. 소유 세션 허가나 서버 처리 결과를 대신하지 않는다. */
data class BleDeviceStatus(val batteryPercent: Long?, val batteryMillivolts: Long?, val charging: Boolean?,
    val pendingRecordCount: Long?, val ownerFlow: BleOwnerFlowStatus = BleOwnerFlowStatus())

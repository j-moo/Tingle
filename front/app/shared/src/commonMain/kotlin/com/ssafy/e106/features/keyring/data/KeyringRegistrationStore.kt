package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.storage.*
import kotlinx.serialization.Serializable

/** 서명은 해당 회원/challenge에 묶인 공개 증명이다. 개인키·로그인 토큰은 저장하지 않는다. */
@Serializable data class PendingKeyringRegistration(val owner: Long, val serial: String,
    val challengeKey: String, val registerKey: String, val body: RegisterKeyringBody? = null,
    val receipt: OwnedKeyring? = null) {
    override fun toString() = "PendingKeyringRegistration(REDACTED)"
}
@Serializable private data class RegistrationRecovery(val pending: PendingKeyringRegistration?)

/** 서버 등록을 보내기 전에 복구 기록을 디스크에서 다시 읽어 확인한다. 손상/읽기 실패를 빈 값으로 취급하지 않는다. */
class KeyringRegistrationStore(private val prefs: AppPrefs) {
    private val key = PrefKey("keyring.registration.v1", RegistrationRecovery.serializer(), durable = true)
    suspend fun read(owner: Long): PrefRead<PendingKeyringRegistration?> = when (val r = prefs.read(key, owner)) {
        is PrefRead.Found -> if (valid(r.value.pending, owner)) PrefRead.Found(r.value.pending) else PrefRead.Corrupt
        PrefRead.Missing -> PrefRead.Found(null)
        PrefRead.Corrupt -> PrefRead.Corrupt
        PrefRead.Unreadable -> PrefRead.Unreadable
        PrefRead.OtherUser -> PrefRead.OtherUser
    }
    suspend fun save(owner: Long, pending: PendingKeyringRegistration?): Boolean {
        require(valid(pending, owner))
        // null 완료 표식도 durable 파일에 남겨, 성공 후 이전 요청이 다시 살아나지 않게 한다.
        val record = RegistrationRecovery(pending)
        prefs.set(key, record, owner)
        return (prefs.read(key, owner) as? PrefRead.Found)?.value == record
    }
    private fun valid(p: PendingKeyringRegistration?, owner: Long): Boolean = p == null ||
        (p.owner == owner && KeyringRegistrationProtocol.serial(p.serial) &&
            KeyringRegistrationProtocol.uuid(p.challengeKey) && KeyringRegistrationProtocol.uuid(p.registerKey) &&
            p.challengeKey != p.registerKey &&
            (p.body == null || (p.body.deviceSerial == p.serial && KeyringRegistrationProtocol.validBody(p.body))) &&
            (p.receipt == null || (p.body != null && KeyringRegistrationProtocol.validReceipt(p.receipt))))
}

package com.ssafy.e106.features.keyring.ui

import com.ssafy.e106.features.keyring.data.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 실제 NUS 연결로만 동작한다. 자동 재연결 뒤에도 serial·지원 명령을 다시 확인하고 LF 원문을 보낸다. */
@OptIn(ExperimentalUuidApi::class)
internal class BleKeyringResetPort(private val ble: BleProbeViewModel) : ResetDevicePort {
    override suspend fun challenge(serial: String) = ble.resetExchange(serial) { channel, info ->
        check(info.ownerState in setOf("BOUND", "UNOWNED"))
        val id = Uuid.random().toString()
        KeyringResetProtocol.challengeResponse(channel.exchange(KeyringResetProtocol.challengeRequest(id)), id, serial).also {
            check(it.ownershipEpoch == info.ownershipEpoch)
        }
    }
    override suspend fun result(ticket: ResetTicket) = ble.resetExchange(ticket.serial) { channel, _ ->
        // 소유가 해제되거나 다음 순번으로 바뀌어도 저장된 기존 실행의 증명을 조회할 수 있다.
        val run = requireNotNull(ticket.run); val id = Uuid.random().toString()
        KeyringResetProtocol.result(channel.exchange(KeyringResetProtocol.resultRequest(id, run)), id, run,
            ticket.serial, ticket.start.deviceChallenge.keyId)
    }
    override suspend fun send(ticket: ResetTicket) = ble.resetExchange(ticket.serial) { channel, info ->
        val run = requireNotNull(ticket.run)
        check(info.ownershipEpoch == run.fromOwnershipEpoch && info.ownerState in setOf("BOUND", "UNOWNED"))
        check(Clock.System.now() < Instant.parse(run.commandExpiresAt))
        // requestId도 BE 원문에 포함된다. 앱에서 새 UUID를 넣거나 LF를 덧붙이지 않는다.
        val command = KeyringResetProtocol.command(run, ticket.serial, ticket.start.deviceChallenge.keyId)
        KeyringResetProtocol.result(channel.exchange(command), run.resetRunId, run, ticket.serial, ticket.start.deviceChallenge.keyId)
    }
}

package com.ssafy.e106.features.keyring.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 서버 저장 성공만 전달한다. 값/성향/인증정보는 담지 않고 계정별 변경 횟수로 진행 중 변경도 구분한다.
 * 앱 재시작으로 이 신호가 사라져도 동기화 GET의 stale 검사와 새 연결의 재전송으로 복구한다. */
class KeyringSettingsChanges {
    private val revisions = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val state = revisions.asStateFlow()
    fun saved(owner: Long) { revisions.update { it + (owner to ((it[owner] ?: 0L) + 1L)) } }
}

package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OwnedKeyringsState(
    val owner: Session.SignedIn? = null,
    val items: List<OwnedKeyring>? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
)

/** 화면에 머무는 동안만 목록을 보관한다. 계정 전환·이탈 후 도착한 이전 응답은 버린다. */
class OwnedKeyringsViewModel(private val repository: OwnedKeyringRepository, private val sessions: SessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(OwnedKeyringsState(owner = sessions.state.value as? Session.SignedIn))
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var request: Job? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { session ->
                generation++
                request?.cancel()
                mutable.value = OwnedKeyringsState(owner = session as? Session.SignedIn)
            }
        }
    }

    fun load() {
        val owner = mutable.value.owner ?: return
        request?.cancel()
        val stamp = ++generation
        // 재조회 실패 시 예전 목록을 현재 소유 정보처럼 표시하지 않는다.
        mutable.value = OwnedKeyringsState(owner = owner, loading = true)
        request = viewModelScope.launch {
            try {
                val result = repository.list()
                if (stamp != generation || sessions.state.value != owner) return@launch
                mutable.value = when (result) {
                    is ApiResult.Ok -> OwnedKeyringsState(owner = owner, items = result.value)
                    is ApiResult.Fail -> OwnedKeyringsState(owner = owner, error = result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stamp == generation && sessions.state.value == owner) {
                    mutable.value = OwnedKeyringsState(owner = owner,
                        error = AppError.Unknown(IllegalStateException("Keyring lookup failed")))
                }
            }
        }
    }

    /** 조회 전용이므로 백그라운드·회전·실제 이탈 모두 취소 가능하다. 복귀하면 새 GET으로 확인한다. */
    fun leave() {
        generation++
        request?.cancel()
        mutable.value = OwnedKeyringsState(owner = sessions.state.value as? Session.SignedIn)
    }
}

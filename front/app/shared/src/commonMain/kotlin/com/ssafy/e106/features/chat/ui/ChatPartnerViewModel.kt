package com.ssafy.e106.features.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration

/** 매번 최신 프로필/사진을 다시 받는다. 이전 조회의 이미지 참조는 요청 시작·이탈 때 제거한다. */
class ChatPartnerViewModel(private val api: ChatRepository, private val sessions: SessionStore,
    private val now: () -> Duration = monotonicChatClock()) : ViewModel() {
    private val mutable = MutableStateFlow(ChatPartnerState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var expiry: Job? = null
    private var permissionJob: Job? = null
    private val lifetime = ChatExpiry(now)
    private var lifetimeOwner: Pair<Session.SignedIn, Long>? = null
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        clear(); lifetime.reset(); lifetimeOwner = null
    } } }
    fun clear() { generation++; job?.cancel(); expiry?.cancel(); permissionJob?.cancel(); mutable.value = ChatPartnerState() }
    /** 권한·공개 필드를 확인하되 조회 scope를 초기화하지 않아 깜빡임과 변경 없는 사진 재다운로드를 막는다. */
    fun checkAccess() {
        val scope = mutable.value.scope ?: return
        val owner = sessions.state.value as? Session.SignedIn ?: return
        if (!mutable.value.visible || scope.viewerId != owner.userId || permissionJob?.isActive == true) return
        fun current() = mutable.value.scope == scope && sessions.state.value == owner && mutable.value.visible
        permissionJob = viewModelScope.launch {
            try {
                val requestStarted = now()
                when (val result = api.room(scope.chatRoomId)) {
                    is ApiResult.Fail -> if (current() && result.error.revokesChat()) {
                        job?.cancel(); expiry?.cancel()
                        mutable.value = mutable.value.failed(scope, result.error.chatCode())
                    }
                    is ApiResult.Ok -> if (current()) {
                        val access = result.value
                        require(access.chatRoomId == scope.chatRoomId && access.status in setOf("ACTIVE", "CLOSED"))
                        lifetime.observe(access, requestStarted)
                        val remaining = lifetime.remaining()
                        if (access.status == "CLOSED" || !access.canRead || remaining <= 0) {
                            job?.cancel(); expiry?.cancel(); mutable.value = mutable.value.failed(scope, "CHAT_CLOSED")
                        } else {
                            expiry?.cancel()
                            expiry = viewModelScope.launch { delay(remaining); if (current()) {
                                job?.cancel(); mutable.value = mutable.value.failed(scope, "CHAT_CLOSED")
                            } }
                            // 방 권한은 선택 정보 공개 동의와 별개다. 화면을 비우지 않고 최신 공개 필드만 갱신한다.
                            when (val profile = api.profile(scope.chatRoomId)) {
                                is ApiResult.Fail -> if (current() && profile.error.revokesChat()) {
                                    job?.cancel(); expiry?.cancel(); mutable.value = mutable.value.failed(scope, profile.error.chatCode())
                                }
                                is ApiResult.Ok -> if (current()) {
                                    val p = profile.value
                                    val hadPhoto = mutable.value.profile?.hasProfilePhoto == true
                                    mutable.value = mutable.value.copy(profile = p.publicProfile(),
                                        photo = when { !p.hasProfilePhoto -> ChatPartnerPhoto.Empty
                                            !hadPhoto -> ChatPartnerPhoto.Loading; else -> mutable.value.photo },
                                        image = mutable.value.image.takeIf { p.hasProfilePhoto })
                                    if (p.hasProfilePhoto && !hadPhoto) loadPhoto(scope, ::current)
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 조회 실패에도 기존 만료 타이머는 유지하며 다음 주기에 재확인한다. */ }
        }
    }
    fun load(room: Long) {
        val owner = sessions.state.value as? Session.SignedIn ?: return
        // 같은 계정·방의 새로고침에도 이미 확인한 종료 시각은 보존한다.
        if (lifetimeOwner != (owner to room)) { lifetime.reset(); lifetimeOwner = owner to room }
        clear(); val scope = ChatPartnerScope(owner.userId, room, generation)
        mutable.value = ChatPartnerState().begin(scope)
        fun current() = mutable.value.scope == scope && sessions.state.value == owner
        job = viewModelScope.launch {
            try {
                val requestStarted = now()
                val permission = api.room(room)
                if (!current()) return@launch
                if (permission is ApiResult.Fail) { mutable.value = mutable.value.failed(scope, permission.error.chatCode()); return@launch }
                val access = (permission as ApiResult.Ok).value
                lifetime.observe(access, requestStarted)
                val remaining = lifetime.remaining()
                if (access.chatRoomId != room || access.status != "ACTIVE" || !access.canRead || remaining <= 0) {
                    mutable.value = mutable.value.failed(scope, "CHAT_CLOSED"); return@launch
                }
                expiry = viewModelScope.launch { delay(remaining); if (current()) {
                    job?.cancel(); mutable.value = mutable.value.failed(scope, "CHAT_CLOSED")
                } }
                when (val response = api.profile(room)) {
                    is ApiResult.Fail -> if (current()) mutable.value = mutable.value.failed(scope, response.error.chatCode())
                    is ApiResult.Ok -> if (current()) {
                        val p = response.value
                        mutable.value = mutable.value.loaded(scope, p.publicProfile())
                        if (p.hasProfilePhoto) loadPhoto(scope, ::current)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current()) mutable.value = mutable.value.failed(scope, "UNAVAILABLE") }
        }
    }
    private fun ChatPartnerRecord.publicProfile() = PublicChatPartner(nickname, age, gender,
        smokingStatus, drinkingFrequency, religion, introduction, hasProfilePhoto)
    private suspend fun loadPhoto(scope: ChatPartnerScope, current: () -> Boolean) {
        when (val photo = api.photo(scope.chatRoomId)) {
            is ApiResult.Fail -> if (current()) mutable.value = if (photo.error.revokesChat())
                mutable.value.failed(scope, photo.error.chatCode()) else mutable.value.photoFailed(scope)
            is ApiResult.Ok -> {
                val bitmap = decodeChatImage(photo.value)
                if (current()) mutable.value = if (bitmap == null) mutable.value.photoFailed(scope)
                    else mutable.value.photoLoaded(ChatPartnerImage(scope, bitmap))
            }
        }
    }
}

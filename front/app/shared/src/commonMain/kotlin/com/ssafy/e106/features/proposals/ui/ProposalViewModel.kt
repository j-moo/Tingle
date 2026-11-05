package com.ssafy.e106.features.proposals.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.proposals.ProposalAccess
import com.ssafy.e106.features.proposals.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi

enum class ProposalPageMode { List, Profile, Result }
data class ConnectedProposalState(
    val session: Session.SignedIn? = null,
    val page: ProposalPageMode = ProposalPageMode.List,
    val list: ProposalListState = ProposalListState(),
    val review: ProposalReviewState? = null,
    val result: ProposalDecisionState? = null,
    val selectedId: Long? = null,
    val chatRoomId: Long? = null,
    val busy: Boolean = false,
    // 자동 GET 중에는 기존 대기 안내를 유지한다. busy는 중복 요청만 막고 목록 복귀는 허용한다.
    val refreshingResult: Boolean = false,
    val error: AppError? = null,
    val pending: PendingDecision? = null,
    val storageIssue: Boolean = false,
)



/** 화면과 요청 수명을 분리한다. 회전은 쓰기를 취소하지 않고, 계정 변경은 이전 결과를 폐기한다. */
@OptIn(ExperimentalUuidApi::class)
class ProposalViewModel(
    private val repository: ProposalRepository,
    private val sessions: SessionStore,
    private val prefs: AppPrefs,
    private val access: ProposalAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedProposalState())
    val state = mutable.asStateFlow()
    private val pendingStore = ProposalPendingStore(prefs)
    private var generation = 0L
    private var job: Job? = null
    private var writing = false
    private var nextCursor: String? = null
    private var started = false
    private var reviewRequired = false
    private var resultUpdates: Job? = null

    /** 화면이 보이는 동안 확정 대기 결과만 조회한다. 미확인 쓰기는 자동으로 재전송하지 않는다. */
    private fun startResultUpdates() {
        if (resultUpdates?.isActive == true) return
        resultUpdates = viewModelScope.launch {
            mutable.map { s ->
                s.selectedId.takeIf { !s.storageIssue && s.pending == null && s.error == null &&
                    s.page == ProposalPageMode.Result && s.result?.stage in setOf(
                        ProposalDecisionStage.Waiting, ProposalDecisionStage.MatchedPreparing) }
            }.distinctUntilChanged().collectLatest { id ->
                if (id != null) while (currentCoroutineContext().isActive) {
                    // 기기 시각으로 만료를 추정하지 않는다. 응답 완료 뒤 5초를 기다리므로 느린 요청도 겹치지 않는다.
                    delay(5_000)
                    if (!mutable.value.busy) checkResult(automatic = true)
                    job?.join()
                }
            }
        }
    }

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { session ->
                generation++; job?.cancel(); writing = false; nextCursor = null; started = false; reviewRequired = false
                mutable.value = ConnectedProposalState(session = session as? Session.SignedIn)
            }
        }
    }
    private fun valid(stamp: Long) = stamp == generation && mutable.value.session == sessions.state.value
    private fun invalid() = AppError.Unknown(IllegalStateException("Invalid proposal response"))
    private fun inaccessible(error: AppError) = error is AppError.Server &&
        (error.status == 404 || error.code == "PROPOSAL_EXPIRED")
    private fun work(automatic: Boolean = false, block: suspend (Long, Long) -> Unit) {
        val s = mutable.value; val session = s.session ?: return
        if (s.busy || session != sessions.state.value || !access.allowed(session)) return
        val stamp = ++generation
        mutable.update { it.copy(busy = true, refreshingResult = automatic, error = null) }
        job = viewModelScope.launch {
            try { block(session.userId, stamp) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (valid(stamp)) mutable.update { it.copy(error = invalid(),
                list = it.list.copy(load = ProposalListLoad.Failed),
                review = it.review?.copy(stage = ProposalReviewStage.Failed, latest = null, confirmation = null),
                result = if (writing) ProposalDecisionState(ProposalDecisionStage.Unknown) else it.result) } }
            finally { if (valid(stamp)) { writing = false; mutable.update { it.copy(busy = false, refreshingResult = false) } } }
        }
    }

    fun resume() {
        // 회전 중 쓰기가 진행 중이어도 새 화면의 조회 수명은 복구한다. 쓰기 자체는 기존 작업이 마친다.
        startResultUpdates()
        if (mutable.value.busy) return
        if (!started) {
            work { user, stamp ->
                // 읽기 실패를 빈 기록으로 간주하면 새 결정이 이전 요청을 덮어쓴다.
                val record = pendingStore.read(user)
                if (!valid(stamp)) return@work
                val restored = when(record) {
                    is PrefRead.Found -> record.value
                    PrefRead.Missing -> null
                    PrefRead.OtherUser -> return@work
                    else -> { storageFailed(); return@work }
                }
                mutable.update { it.copy(storageIssue = false, pending = restored) }
                if (!valid(stamp)) return@work
                started = true
                if (restored != null) mutable.update { it.copy(pending = restored, selectedId = restored.proposalId,
                    page = ProposalPageMode.Result, result = ProposalDecisionState(ProposalDecisionStage.Unknown)) }
                else readList(null, stamp)
            }
        } else when (mutable.value.page) {
            ProposalPageMode.List -> refresh()
            ProposalPageMode.Profile -> reloadProfile()
            ProposalPageMode.Result -> if (mutable.value.pending == null) checkResult()
        }
    }

    fun pause() {
        // 실제 화면 이탈/백그라운드에서는 예약된 조회도 중지한다. 회전 후 resume에서 다시 시작한다.
        resultUpdates?.cancel(); resultUpdates = null
        // 사진/확인 창은 숨겨진 동안 보존하지 않는다. 진행 중인 쓰기는 ViewModel에서 계속 받는다.
        if (!writing) { generation++; job?.cancel(); mutable.update { it.copy(busy = false, refreshingResult = false) } }
        mutable.update { s -> s.copy(list = s.list.copy(items = emptyList()), review = s.review?.copy(latest = null, confirmation = null)) }
    }
    private fun storageFailed() {
        started = false
        mutable.update { it.copy(storageIssue = true, list = ProposalListState(ProposalListLoad.Failed)) }
    }
    fun refresh() {
        // 사용자가 누르지 않은 자동 조회 때문에 이동을 막지 않는다. 목록 조회를 시작하기 전에
        // 이전 응답 세대를 무효화해 취소를 늦게 처리하는 GET도 결과 화면을 다시 열지 못하게 한다.
        // 수락/거절 쓰기와 수동 복구는 refreshingResult가 아니므로 기존 busy 보호를 유지한다.
        if (mutable.value.refreshingResult) {
            generation++; job?.cancel(); job = null
            mutable.update { it.copy(busy = false, refreshingResult = false) }
        }
        if (!started) resume() else work { _, stamp -> readList(null, stamp) }
    }
    fun more() {
        val cursor = nextCursor ?: return
        work { _, stamp -> readList(cursor, stamp) }
    }
    private suspend fun readList(cursor: String?, stamp: Long) {
        mutable.update { it.copy(page = ProposalPageMode.List, review = null, result = null, selectedId = null,
            list = if (cursor == null) ProposalListState() else it.list.copy(page = ProposalPageState.Loading)) }
        when (val response = repository.list(cursor)) {
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = response.error,
                list = if (cursor == null) ProposalListState(ProposalListLoad.Failed) else it.list.copy(page = ProposalPageState.Failed)) }
            is ApiResult.Ok -> {
                if (!valid(stamp)) return
                val page = response.value
                if (page.items.any { it.proposalId <= 0 } || cursor != null && page.nextCursor == cursor) {
                    mutable.update { it.copy(error = invalid(), list = it.list.copy(load = ProposalListLoad.Failed)) }; return
                }
                nextCursor = page.nextCursor
                // 프로필 요청을 최대 네 개로 제한한다. 한 사진 실패가 전체 목록을 실패시키지 않는다.
                val limit = Semaphore(4)
                val user = mutable.value.session!!.userId
                val entries = coroutineScope {
                    page.items.filter { it.active }.distinctBy { it.proposalId }.map { record -> async {
                        limit.withPermit { listItem(record, user, stamp) }
                    } }.awaitAll().filterNotNull()
                }
                if (valid(stamp)) mutable.update { it.copy(list = ProposalListState(ProposalListLoad.Ready,
                    ((if (cursor == null) emptyList() else it.list.items) + entries).distinctBy { row -> row.proposalId },
                    if (nextCursor == null) ProposalPageState.End else ProposalPageState.More)) }
            }
        }
    }
    private suspend fun listItem(record: ProposalRecord, user: Long, stamp: Long): ProposalListItem? {
        val base = ProposalListItem(record.proposalId, null, null, decision(record), record.expiresAt,
            photoState = ProposalPhotoState.Failed)
        return when (val result = repository.profile(record.proposalId)) {
            is ApiResult.Fail -> if (inaccessible(result.error)) null else base
            is ApiResult.Ok -> {
                if (!valid(stamp)) return null
                val p = result.value
                val photo = loadPhoto(p, ProposalPhotoRequest(user, record.proposalId, stamp))
                if (photo.first == ProposalPhotoState.Unavailable) null
                else base.copy(nickname = p.nickname, age = p.age, photoState = photo.first,
                    photoRequest = photo.second?.request, photoImage = photo.second)
            }
        }
    }

    fun open(id: Long) {
        if (!started || mutable.value.storageIssue || mutable.value.pending != null || mutable.value.busy || id <= 0) return
        reviewRequired = false
        mutable.update { it.copy(selectedId = id, page = ProposalPageMode.Profile) }
        reloadProfile()
    }
    fun reloadProfile() {
        val id = mutable.value.selectedId ?: return
        work { user, stamp ->
            val context = ProposalReviewContext(user, id, stamp)
            mutable.update { it.copy(page = ProposalPageMode.Profile, result = null,
                list = it.list.copy(items = emptyList()), review = ProposalReviewState(context, ProposalReviewStage.Loading)) }
            when (val detail = repository.get(id)) {
                is ApiResult.Fail -> failProfile(detail.error, stamp)
                is ApiResult.Ok -> {
                    if (!valid(stamp)) return@work
                    if (detail.value.proposalId != id) { failProfile(invalid(), stamp); return@work }
                    if (!detail.value.active) { showResult(detail.value); return@work }
                    when (val profile = repository.profile(id)) {
                        is ApiResult.Fail -> failProfile(profile.error, stamp)
                        is ApiResult.Ok -> {
                            if (!valid(stamp)) return@work
                            val p = profile.value
                            val photo = loadPhoto(p, ProposalPhotoRequest(user, id, stamp))
                            if (!valid(stamp)) return@work
                            if (photo.first == ProposalPhotoState.Unavailable) {
                                failProfile(AppError.Server(410, "PROPOSAL_EXPIRED", ""), stamp); return@work
                            }
                            val snapshot = ReviewedProposalProfile(context, p.profileVersion, ProposalProfileState(
                                load = ProposalProfileLoad.Ready, myDecision = decision(detail.value),
                                reviewRequired = reviewRequired, reason = MatchReasonState(accessible = false),
                                canRespond = detail.value.status == "PENDING" && detail.value.myDecision == "NONE" && mutable.value.pending == null,
                                deadlineLabel = detail.value.expiresAt,
                                profile = PublicProposalProfile(p.nickname, p.age, p.gender, p.smokingStatus, p.drinkingFrequency,
                                    p.religionDisplay, p.introduction, photo.first, photo.second?.request, photo.second)))
                            if (!snapshot.valid) { failProfile(invalid(), stamp); return@work }
                            mutable.update { it.copy(review = it.review?.received(snapshot)) }
                        }
                    }
                }
            }
        }
    }
    private suspend fun loadPhoto(p: PartnerProfile, request: ProposalPhotoRequest): Pair<ProposalPhotoState, ProposalPhotoImage?> {
        if (!p.hasProfilePhoto) return ProposalPhotoState.NotProvided to null
        return when (val response = repository.photo(request.proposalId)) {
            is ApiResult.Fail -> when {
                inaccessible(response.error) -> ProposalPhotoState.Unavailable to null
                (response.error as? AppError.Server)?.code == "PHOTO_REMOVED" -> ProposalPhotoState.Removed to null
                else -> ProposalPhotoState.Failed to null
            }
            is ApiResult.Ok -> {
                if (!valid(request.generation)) return ProposalPhotoState.Failed to null
                val bitmap = decodeProposalImage(response.value)
                if (bitmap == null) ProposalPhotoState.Failed to null else ProposalPhotoState.Ready to ProposalPhotoImage(request, bitmap)
            }
        }
    }
    private fun failProfile(error: AppError, stamp: Long) {
        if (valid(stamp)) mutable.update { it.copy(error = error, review = it.review?.copy(
            stage = if (inaccessible(error)) ProposalReviewStage.Unavailable else ProposalReviewStage.Failed, latest = null, confirmation = null)) }
    }
    fun ask(choice: ProposalChoice, expected: ProposalReviewContext? = mutable.value.review?.context) {
        if (!started || mutable.value.storageIssue || mutable.value.busy || mutable.value.pending != null) return
        if (mutable.value.review?.context != expected) return
        mutable.update { s -> s.copy(review = s.review?.let { it.copy(confirmation = it.ask(choice)) }) }
    }
    fun dismiss() { if (!mutable.value.busy) mutable.update { it.copy(review = it.review?.copy(confirmation = null)) } }
    fun revokeProfile(context: ProposalReviewContext) {
        // 부가 설명 조회에서도 동일 제안의 권한 상실을 확인하면 기존 공개 정보·사진을 숨긴다.
        if (!writing && mutable.value.review?.context == context) {
            generation++; job?.cancel()
            mutable.update { it.copy(busy = false, review = it.review?.copy(stage = ProposalReviewStage.Unavailable,
                latest = null, confirmation = null), list = it.list.copy(items = emptyList())) }
        }
    }
    fun submit(expected: ProposalReviewContext? = mutable.value.review?.context) {
        if (!started || mutable.value.storageIssue) return
        val confirmation = mutable.value.review?.submitAction?.confirmation ?: return
        if (confirmation.snapshot.context != expected) return
        val user = mutable.value.session?.userId ?: return
        if (mutable.value.pending != null) return
        val pending = PendingDecision(user, confirmation.snapshot.context.proposalId,
            if (confirmation.choice == ProposalChoice.Accept) "ACCEPT" else "DECLINE", Uuid.random().toString(),
            confirmation.snapshot.profileVersion.takeIf { confirmation.choice == ProposalChoice.Accept })
        work { _, stamp ->
            // 영속 기록을 다시 읽어 같은 키/본문이 저장됐음을 확인한 뒤에만 전송한다.
            // 기록 저장 도중 화면이 잠시 숨겨져도 중단하지 않아 저장/전송 사이의 빈틈을 막는다.
            writing = true
            val saved = pendingStore.save(user, pending)
            if (!valid(stamp)) return@work
            if (saved !is PrefRead.Found) { if (saved != PrefRead.OtherUser) storageFailed(); return@work }
            mutable.update { it.copy(pending = pending) }
            send(pending, stamp)
        }
    }
    private suspend fun send(pending: PendingDecision, stamp: Long) {
        writing = true
        mutable.update { it.copy(page = ProposalPageMode.Result, selectedId = pending.proposalId, review = null,
            result = ProposalDecisionState(ProposalDecisionStage.Checking), list = it.list.copy(items = emptyList())) }
        when (val response = repository.decide(pending)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                if (response.value.proposalId == pending.proposalId && response.value.myDecision == pending.choice) {
                    if (clearPending(pending.owner, stamp) && valid(stamp)) showResult(response.value)
                } else mutable.update { it.copy(error = invalid(), result = ProposalDecisionState(ProposalDecisionStage.Unknown)) }
            }
            is ApiResult.Fail -> if (valid(stamp)) {
                val code = (response.error as? AppError.Server)?.code
                when {
                    code == "PROFILE_CHANGED" -> {
                        reviewRequired = true
                        if (!clearPending(pending.owner, stamp)) return
                        if (valid(stamp)) mutable.update { it.copy(result = ProposalDecisionState(ProposalDecisionStage.ProfileChanged)) }
                    }
                    inaccessible(response.error) -> {
                        if (!clearPending(pending.owner, stamp)) return
                        if (valid(stamp)) mutable.update { it.copy(result = ProposalDecisionState(ProposalDecisionStage.Unavailable)) }
                    }
                    code == "PROPOSAL_STATE_CONFLICT" || code == "IDEMPOTENCY_CONFLICT" -> {
                        // 다른 단말의 확정 선택을 GET으로 확인한다. 충돌을 거절/성사로 추정하지 않는다.
                        when (val current = repository.get(pending.proposalId)) {
                            is ApiResult.Ok -> if (valid(stamp) && current.value.proposalId == pending.proposalId &&
                                current.value.myDecision in setOf("ACCEPT", "DECLINE")) {
                                if (!clearPending(pending.owner, stamp)) return
                                if (valid(stamp)) showResult(current.value)
                            } else if (valid(stamp)) mutable.update { it.copy(error = response.error, result = ProposalDecisionState(ProposalDecisionStage.Unknown)) }
                            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = current.error, result = ProposalDecisionState(ProposalDecisionStage.Unknown)) }
                        }
                    }
                    else -> mutable.update { it.copy(error = response.error, result = ProposalDecisionState(ProposalDecisionStage.Unknown)) }
                }
            }
        }
    }
    private suspend fun clearPending(owner: Long, stamp: Long): Boolean {
        // 완료 표식까지 확인해야 과거 캐시나 삭제 실패로 옛 요청이 되살아나지 않는다.
        val saved = pendingStore.save(owner, null)
        if (!valid(stamp)) return false
        if (saved !is PrefRead.Found) {
            if (saved != PrefRead.OtherUser) storageFailed()
            return false
        }
        mutable.update { it.copy(pending = null) }
        return true
    }
    fun recover() {
        if (!started || mutable.value.storageIssue) { resume(); return }
        val p = mutable.value.pending ?: return
        // 응답 유실 복구는 동일 키/동일 본문으로만 한다. 새 수락으로 바꾸지 않는다.
        work { user, stamp -> if (p.owner == user) send(p, stamp) }
    }
    fun checkResult() = checkResult(automatic = false)

    private fun checkResult(automatic: Boolean) {
        // 자동 갱신은 GET만 허용한다. 저장/응답 유실 복구는 사용자의 명시적 재시도로 남긴다.
        if (automatic && (!started || mutable.value.storageIssue || mutable.value.pending != null)) return
        if (!started || mutable.value.storageIssue) { resume(); return }
        if (mutable.value.pending != null) { recover(); return }
        val id = mutable.value.selectedId ?: return
        work(automatic = automatic) { _, stamp -> when (val response = repository.get(id)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                if (response.value.proposalId == id) showResult(response.value) else mutable.update { it.copy(error = invalid()) }
            }
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = response.error,
                result = ProposalDecisionState(if (inaccessible(response.error)) ProposalDecisionStage.Unavailable else ProposalDecisionStage.Unknown)) }
        } }
    }
    private fun showResult(p: ProposalRecord) {
        val stage = when {
            p.status == "WAITING_FOR_OTHER" && p.myDecision == "ACCEPT" -> ProposalDecisionStage.Waiting
            p.status == "ACCEPTED" && p.myDecision == "ACCEPT" -> if (p.chatAvailable && (p.chatRoomId ?: 0) > 0)
                ProposalDecisionStage.MatchedReady else ProposalDecisionStage.MatchedPreparing
            p.status == "REJECTED" && p.myDecision == "DECLINE" -> ProposalDecisionStage.Rejected
            p.status == "REJECTED" && p.myDecision == "ACCEPT" -> ProposalDecisionStage.NotMatched
            p.status == "EXPIRED" -> ProposalDecisionStage.Expired
            else -> ProposalDecisionStage.Unknown
        }
        mutable.update { it.copy(page = ProposalPageMode.Result, review = null, selectedId = p.proposalId,
            result = ProposalDecisionState(stage, p.expiresAt),
            chatRoomId = p.chatRoomId.takeIf { stage == ProposalDecisionStage.MatchedReady }) }
    }
    private fun decision(p: ProposalRecord) = if (p.myDecision == "ACCEPT") MyProposalDecision.AcceptedWaiting else MyProposalDecision.NotResponded
}

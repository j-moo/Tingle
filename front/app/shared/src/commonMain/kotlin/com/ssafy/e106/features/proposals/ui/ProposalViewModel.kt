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
import kotlin.time.TimeSource
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
    // 서로 수락 · 채팅방 확인됨: 화면이 바로 채팅을 열고 chatOpened() 로 지운다
    val openChat: Long? = null,
    val busy: Boolean = false,
    // 수락 · 거절을 서버에 보내는 중(이때만 화면 이동을 막는다. 조회는 뒤로 가면 취소)
    val writing: Boolean = false,
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
    private val accepted = AcceptedProposalStore(prefs)
    private var generation = 0L
    private var job: Job? = null
    private var writing = false
        set(value) { field = value; mutable.update { it.copy(writing = value) } }
    // 첫 쪽 목록의 조회 번호. 끝난 제안 카드는 이 목록이 그대로일 때만 붙인다
    private var listStamp = 0L
    // 화면이 숨겨진 동안(pause~resume)엔 쓰기가 끝나도 프로필 · 목록 · 사진을 받지 않는다(돌아오면 resume 이 받는다)
    // 첫 resume 전엔 숨겨진 것으로 본다(화면이 아직 없음)
    private var hidden = true
    // 숨기기 직전 "수락했어요"를 보고 있었는지: 돌아올 때 잠깐 실패해도 기다림을 이어 가게
    private var waitingWhenHidden = false
    private var nextCursor: String? = null
    private var started = false
    private var resultUpdates: Job? = null
    // 사진은 목록 · 프로필을 먼저 보여 준 뒤 따로 받는다(그동안 회색 원 + 첫 글자). 화면을 떠나거나 계정이 바뀌면 취소
    private val photos = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val photoLimit = Semaphore(4)

    /** 수락하고 기다리는 프로필이 보이는 동안만 결과를 조회한다. 미확인 쓰기는 자동으로 재전송하지 않는다. */
    private fun startResultUpdates() {
        if (resultUpdates?.isActive == true) return
        resultUpdates = viewModelScope.launch {
            mutable.map { s ->
                s.selectedId.takeIf { !s.storageIssue && s.pending == null && s.error == null && s.openChat == null && (
                    s.page == ProposalPageMode.Profile && s.review?.current?.profile?.myDecision == MyProposalDecision.AcceptedWaiting ||
                    // 기다리다 숨겼다 돌아와 확인 중(쓰기 복구의 확인 중은 pending 이 있어 제외)
                    s.page == ProposalPageMode.Result && s.result?.stage == ProposalDecisionStage.Checking) }
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
                generation++; job?.cancel(); photos.cancelChildren(); writing = false; nextCursor = null; started = false
                mutable.value = ConnectedProposalState(session = session as? Session.SignedIn)
                // 보이는 중에 세션 값만 바뀌면(토큰 재발급으로 가입 상태 갱신 등) ON_RESUME 이 다시 오지 않으므로 여기서 다시 받는다
                if (!hidden) resume()
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
                list = it.list.copy(load = ProposalListLoad.Failed, refreshing = false),
                review = if (writing) null else it.review?.copy(stage = ProposalReviewStage.Failed, latest = null, confirmation = null),
                page = if (writing) ProposalPageMode.Result else it.page,
                result = if (writing || it.result?.stage == ProposalDecisionStage.Checking) ProposalDecisionState(ProposalDecisionStage.Unknown) else it.result) } }
            finally { if (valid(stamp)) { writing = false; mutable.update { it.copy(busy = false, refreshingResult = false) } } }
        }
    }

    fun resume() {
        // 회전 중 쓰기가 진행 중이어도 새 화면의 조회 수명은 복구한다. 쓰기 자체는 기존 작업이 마친다.
        startResultUpdates()
        val wasHidden = hidden
        hidden = false
        val wasWaiting = waitingWhenHidden; waitingWhenHidden = false
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
        } else if (wasHidden) when (mutable.value.page) {   // 숨겨졌다 돌아올 때만 다시 받는다(대화상자 등으로 ON_RESUME 만 오면 그대로)
            ProposalPageMode.List -> refresh()
            // 기다리던 중이었으면 "확인 중"에서 자동 확인(잠깐 실패해도 이어서 확인, 기다림이면 프로필로). 아니면 프로필을 다시 받는다
            ProposalPageMode.Profile -> if (wasWaiting) { showStage(ProposalDecisionStage.Checking); checkResult(automatic = true) }
                else reloadProfile()
            ProposalPageMode.Result -> when {
                mutable.value.pending != null -> Unit
                mutable.value.result?.stage == ProposalDecisionStage.Checking -> checkResult(automatic = true)
                else -> checkResult()
            }
        }
    }

    fun pause() {
        // 실제 화면 이탈/백그라운드에서는 예약된 조회도 중지한다. 회전 후 resume에서 다시 시작한다.
        resultUpdates?.cancel(); resultUpdates = null
        photos.cancelChildren()
        if (!hidden) waitingWhenHidden = mutable.value.page == ProposalPageMode.Profile &&
            mutable.value.review?.current?.profile?.myDecision == MyProposalDecision.AcceptedWaiting
        hidden = true
        // 사진/확인 창은 숨겨진 동안 보존하지 않는다. 진행 중인 쓰기는 ViewModel에서 계속 받는다.
        if (!writing) { generation++; job?.cancel(); mutable.update { it.copy(busy = false, refreshingResult = false) } }
        mutable.update { s -> s.copy(list = ProposalListState(), review = s.review?.copy(latest = null, confirmation = null)) }
    }
    private fun storageFailed() {
        started = false
        mutable.update { it.copy(storageIssue = true, list = ProposalListState(ProposalListLoad.Failed)) }
    }
    /**
     * 진행 중인 조회(자동 확인 · 프로필 · 목록)는 취소한다. 이전 응답 세대를 무효화해 늦게 끝나는 GET 도 화면을 바꾸지 못한다.
     * 수락 · 거절 쓰기와 그 복구는 취소하지 않는다(busy 보호 유지).
     */
    private fun cancelReads() {
        if (mutable.value.busy && !writing) {
            generation++; job?.cancel(); job = null
            mutable.update { it.copy(busy = false, refreshingResult = false) }
        }
    }
    fun refresh() {
        cancelReads()
        if (!started) resume() else work { _, stamp -> readList(null, stamp) }
    }
    /** 푸시가 상세 화면을 닫거나 확인 중인 결정을 덮어쓰지 않도록 목록에서만 다시 조회한다. */
    internal fun refreshAfterProposalPush(owner: Session.SignedIn): Boolean {
        val current = mutable.value
        if (hidden || current.session != owner || sessions.state.value != owner || current.page != ProposalPageMode.List ||
            writing || current.pending != null || current.storageIssue || current.openChat != null) return false
        // 첫 조회가 로컬의 미확인 결정을 복구 중이면 중단하지 않는다.
        if (started || !current.busy) refresh()
        return true
    }
    fun more() {
        val cursor = nextCursor ?: return
        work { _, stamp -> readList(cursor, stamp) }
    }
    private suspend fun readList(cursor: String?, stamp: Long) {
        if (hidden) {
            if (cursor == null) photos.cancelChildren()   // 숨겨진 동안 끝난 쓰기(거절 등): 목록으로만 돌려 두고 받는 건 resume 에
            mutable.update { it.copy(page = ProposalPageMode.List, review = null, result = null, selectedId = null, list = ProposalListState()) }
            return
        }
        // 이미 목록이 보이면(당겨서 새로고침) 목록은 두고 위쪽 표시만 돌린다. 실패해도 보던 목록 + 안내 한 줄
        mutable.update { it.copy(page = ProposalPageMode.List, review = null, result = null, selectedId = null,
            list = when {
                cursor != null -> it.list.copy(page = ProposalPageState.Loading)
                it.page == ProposalPageMode.List && it.list.load == ProposalListLoad.Ready && it.list.items.isNotEmpty() ->
                    it.list.copy(refreshing = true, refreshFailed = false,
                        page = if (it.list.page == ProposalPageState.Loading) ProposalPageState.More else it.list.page)
                else -> ProposalListState()
            }) }
        // 응답이 아니라 요청 시작을 기준으로 잡아 네트워크 지연을 남은 시간에 더하지 않는다(채팅 ChatExpiry 와 같은 방식)
        val requested = TimeSource.Monotonic.markNow()
        when (val response = repository.list(cursor)) {
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = response.error,
                list = when {
                    cursor != null -> it.list.copy(page = ProposalPageState.Failed)
                    it.list.refreshing -> it.list.copy(refreshing = false, refreshFailed = true)
                    else -> ProposalListState(ProposalListLoad.Failed)
                }) }
            is ApiResult.Ok -> {
                if (!valid(stamp)) return
                if (hidden) return   // 받는 사이 숨겨졌으면 쓰지 않는다(resume 이 다시 받는다)
                val page = response.value
                if (page.items.any { it.proposalId <= 0 } || cursor != null && page.nextCursor == cursor) {
                    mutable.update { it.copy(error = invalid(), list = it.list.copy(load = ProposalListLoad.Failed, refreshing = false)) }; return
                }
                nextCursor = page.nextCursor
                // 프로필 요청을 최대 네 개로 제한한다. 한 사진 실패가 전체 목록을 실패시키지 않는다.
                val limit = Semaphore(4)
                val user = mutable.value.session!!.userId
                val loaded = coroutineScope {
                    page.items.filter { it.active }.distinctBy { it.proposalId }.map { record -> async {
                        limit.withPermit { listItem(record, user, stamp,
                            remainingUntil(record.expiresAt, page.serverTime)?.let { requested + it }) }
                    } }.awaitAll().filterNotNull()
                }
                // 새로고침 전 이미 받은 사진은 그대로 쓴다(다시 받지 않아 깜빡이지 않게)
                val before = mutable.value.list.items.filter { it.photoState == ProposalPhotoState.Ready }.associateBy { it.proposalId }
                val entries = loaded.map { (item, _) -> before[item.proposalId]?.takeIf { item.photoState == ProposalPhotoState.Loading }
                    ?.let { item.copy(photoState = it.photoState, photoRequest = it.photoRequest, photoImage = it.photoImage) } ?: item }
                if (!valid(stamp) || hidden) return
                if (cursor == null) { listStamp = stamp; photos.cancelChildren() }   // 새 목록이 왔으니 이전 목록의 사진 · 끝난 제안 조회는 버린다
                if (valid(stamp)) mutable.update { it.copy(list = ProposalListState(ProposalListLoad.Ready,
                    ((if (cursor == null) emptyList() else it.list.items) + entries).distinctBy { row -> row.proposalId },
                    if (nextCursor == null) ProposalPageState.End else ProposalPageState.More)) }
                if (valid(stamp)) listPhotos(loaded.mapNotNull { (item, p) -> p?.takeIf { item.proposalId !in before }?.let { item.photoRequest!! to it } })
                // 첫 쪽일 때만: 내가 수락했는데 목록에서 빠진 제안의 결과(매칭 안 됨 · 기한 지남)를 나중에 맨 앞에 한 번
                if (cursor == null && valid(stamp)) endedLater(user, page.items.filter { it.active }.map { it.proposalId }.toSet(), stamp)
            }
        }
    }
    /** 목록 사진을 4개씩 받아 해당 줄에만 채운다. 그 줄의 요청 번호가 그대로일 때만(새로고침 · 숨김 뒤엔 버림) */
    private fun listPhotos(targets: List<Pair<ProposalPhotoRequest, PartnerProfile>>) {
        if (targets.isEmpty() || hidden) return
        targets.forEach { (request, p) -> viewModelScope.launch(photos) { photoLimit.withPermit {
            val (state, image) = loadPhoto(p, request)
            mutable.update { s -> s.copy(list = s.list.copy(items = s.list.items.mapNotNull { item ->
                when {
                    item.photoRequest != request -> item
                    state == ProposalPhotoState.Unavailable -> null   // 볼 수 없게 된 제안은 목록에서 뺀다
                    else -> item.copy(photoState = state, photoImage = image)
                }
            })) }
        } } }
    }
    /** 닉네임 · 나이까지만 받아 줄을 만든다. 사진이 있으면 "받는 중"으로 두고 [listPhotos] 가 채운다 */
    private suspend fun listItem(record: ProposalRecord, user: Long, stamp: Long,
        closesAt: TimeSource.Monotonic.ValueTimeMark?): Pair<ProposalListItem, PartnerProfile?>? {
        if (hidden || !valid(stamp)) return null
        val base = ProposalListItem(record.proposalId, null, null, decision(record), closesAt,
            photoState = ProposalPhotoState.Failed)
        return when (val result = repository.profile(record.proposalId)) {
            is ApiResult.Fail -> if (inaccessible(result.error)) null else base to null
            is ApiResult.Ok -> {
                if (!valid(stamp)) return null
                val p = result.value
                base.copy(nickname = p.nickname, age = p.age,
                    photoState = if (p.hasProfilePhoto) ProposalPhotoState.Loading else ProposalPhotoState.NotProvided,
                    photoRequest = ProposalPhotoRequest(user, record.proposalId, stamp)) to p.takeIf { it.hasProfilePhoto }
            }
        }
    }

    /** 저장된 수락 기록 중 첫 쪽에 없는 것만 서버에 물어, 끝났으면 카드로 맨 앞에. 서로 수락(방 있음) · 볼 수 없음은 기록만 지운다 */
    private fun endedLater(user: Long, listed: Set<Long>, stamp: Long) {
        if (hidden) return
        viewModelScope.launch(photos) {
            val drop = mutableSetOf<Long>()
            val cards = coroutineScope {
                accepted.read(user).filter { it.proposalId !in listed }.map { a -> async { photoLimit.withPermit {
                    when (val r = repository.get(a.proposalId)) {
                        is ApiResult.Ok -> {
                            val stage = endedStage(r.value)
                            if (stage == null && r.value.status == "ACCEPTED") drop += a.proposalId
                            stage?.let { ProposalListItem(a.proposalId, null, null, MyProposalDecision.AcceptedWaiting, ended = it) }
                        }
                        // 일시 실패는 기록을 남겨 다음에 다시 묻는다
                        is ApiResult.Fail -> { if (inaccessible(r.error)) drop += a.proposalId; null }
                    }
                } } }.awaitAll().filterNotNull()
            }
            accepted.remove(user, drop)
            if (cards.isNotEmpty()) mutable.update { s ->
                if (listStamp != stamp || s.session?.userId != user || s.page != ProposalPageMode.List) s
                else s.copy(list = s.list.copy(items = (cards + s.list.items).distinctBy { it.proposalId }))
            }
        }
    }
    private fun endedStage(p: ProposalRecord) = when {
        p.status == "REJECTED" && p.myDecision == "ACCEPT" -> ProposalDecisionStage.NotMatched
        p.status == "EXPIRED" -> ProposalDecisionStage.Expired
        else -> null
    }

    fun open(id: Long) {
        cancelReads()
        if (!started || mutable.value.storageIssue || mutable.value.pending != null || mutable.value.busy || id <= 0) return
        mutable.update { it.copy(selectedId = id, page = ProposalPageMode.Profile) }
        reloadProfile()
    }
    fun reloadProfile() {
        val id = mutable.value.selectedId ?: return
        cancelReads()
        work { user, stamp -> loadProfile(user, id, stamp) }
    }
    private suspend fun loadProfile(user: Long, id: Long, stamp: Long) {
        photos.cancelChildren()
        if (hidden) {   // 숨겨진 동안 끝난 쓰기: 프로필 화면으로만 두고 받는 건 resume 에
            mutable.update { it.copy(page = ProposalPageMode.Profile, selectedId = id, result = null, review = null) }
            return
        }
        val context = ProposalReviewContext(user, id, stamp)
        mutable.update { it.copy(page = ProposalPageMode.Profile, result = null, selectedId = id, error = null,
            list = it.list.copy(items = emptyList()), review = ProposalReviewState(context, ProposalReviewStage.Loading)) }
        when (val detail = repository.get(id)) {
            is ApiResult.Fail -> failProfile(detail.error, stamp)
            is ApiResult.Ok -> {
                if (!valid(stamp) || hidden) return   // 받는 사이 숨겨졌으면 resume 이 다시 받는다
                val record = detail.value
                if (record.proposalId != id) { failProfile(invalid(), stamp); return }
                if (!record.active) { apply(record, user, stamp); return }
                when (val profile = repository.profile(id)) {
                    is ApiResult.Fail -> failProfile(profile.error, stamp)
                    is ApiResult.Ok -> {
                        if (!valid(stamp) || hidden) return
                        val p = profile.value
                        val request = ProposalPhotoRequest(user, id, stamp)
                        val snapshot = ReviewedProposalProfile(context, p.profileVersion, ProposalProfileState(
                            load = ProposalProfileLoad.Ready, myDecision = decision(record),
                            reason = MatchReasonState(accessible = false),
                            canRespond = record.status == "PENDING" && record.myDecision == "NONE" && mutable.value.pending == null,
                            profile = PublicProposalProfile(p.nickname, p.age, p.gender, p.smokingStatus, p.drinkingFrequency,
                                p.religionDisplay, p.introduction,
                                if (p.hasProfilePhoto) ProposalPhotoState.Loading else ProposalPhotoState.NotProvided, request)))
                        if (!snapshot.valid) { failProfile(invalid(), stamp); return }
                        mutable.update { it.copy(review = it.review?.received(snapshot)) }
                        if (p.hasProfilePhoto) profilePhoto(p, request)
                    }
                }
            }
        }
    }
    /** 프로필 사진을 따로 받아, 보고 있는 그 프로필(같은 요청 번호)에만 채운다. 볼 수 없게 됐으면 프로필 전체를 닫는다 */
    private fun profilePhoto(p: PartnerProfile, request: ProposalPhotoRequest) {
        if (hidden) return
        viewModelScope.launch(photos) {
            val (state, image) = loadPhoto(p, request)
            mutable.update { s ->
                val review = s.review ?: return@update s
                val old = review.latest?.takeIf { it.profile.profile?.photoRequest == request } ?: return@update s
                if (state == ProposalPhotoState.Unavailable) return@update s.copy(error = AppError.Server(410, "PROPOSAL_EXPIRED", ""),
                    review = review.copy(stage = ProposalReviewStage.Unavailable, latest = null, confirmation = null))
                val new = old.copy(profile = old.profile.copy(profile = old.profile.profile?.copy(photoState = state, photoImage = image)))
                // 열려 있는 수락 · 거절 확인창도 같은 프로필을 가리키게 한다
                s.copy(review = review.copy(latest = new, confirmation = review.confirmation?.let { if (it.snapshot == old) it.copy(snapshot = new) else it }))
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
                // 화면에 맞는지는 부른 쪽이 요청 번호로 확인한다
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
        cancelReads()
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
        // 프로필에서 보냈으면 프로필에 그대로 두고 확인창 버튼만 돈다. 복구(앱 재시작 등)면 결과 화면의 "확인 중"
        if (mutable.value.review?.current?.context?.proposalId == pending.proposalId)
            mutable.update { it.copy(review = it.review?.copy(stage = ProposalReviewStage.Submitting)) }
        else mutable.update { it.copy(page = ProposalPageMode.Result, selectedId = pending.proposalId, review = null,
            result = ProposalDecisionState(ProposalDecisionStage.Checking), list = it.list.copy(items = emptyList())) }
        when (val response = repository.decide(pending)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                if (response.value.proposalId == pending.proposalId && response.value.myDecision == pending.choice) {
                    if (clearPending(pending.owner, stamp) && valid(stamp)) apply(response.value, pending.owner, stamp)
                } else showStage(ProposalDecisionStage.Unknown, invalid())
            }
            is ApiResult.Fail -> if (valid(stamp)) {
                val code = (response.error as? AppError.Server)?.code
                when {
                    code == "PROFILE_CHANGED" -> {
                        // 상대가 공개 정보를 바꿨다: 안내 없이 최신 프로필로 다시 보여 주고, 수락은 다시 누르게 한다
                        if (!clearPending(pending.owner, stamp)) return
                        if (valid(stamp)) loadProfile(pending.owner, pending.proposalId, stamp)
                    }
                    inaccessible(response.error) -> {
                        if (!clearPending(pending.owner, stamp)) return
                        if (valid(stamp)) showStage(ProposalDecisionStage.Unavailable)
                    }
                    code == "PROPOSAL_STATE_CONFLICT" || code == "IDEMPOTENCY_CONFLICT" -> {
                        // 다른 단말의 확정 선택을 GET으로 확인한다. 충돌을 거절/성사로 추정하지 않는다.
                        when (val current = repository.get(pending.proposalId)) {
                            is ApiResult.Ok -> if (valid(stamp) && current.value.proposalId == pending.proposalId &&
                                current.value.myDecision in setOf("ACCEPT", "DECLINE")) {
                                if (!clearPending(pending.owner, stamp)) return
                                if (valid(stamp)) apply(current.value, pending.owner, stamp)
                            } else if (valid(stamp)) showStage(ProposalDecisionStage.Unknown, response.error)
                            is ApiResult.Fail -> if (valid(stamp)) showStage(ProposalDecisionStage.Unknown, current.error)
                        }
                    }
                    else -> showStage(ProposalDecisionStage.Unknown, response.error)
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
    fun checkResult() { cancelReads(); checkResult(automatic = false) }

    private fun checkResult(automatic: Boolean) {
        // 자동 갱신은 GET만 허용한다. 저장/응답 유실 복구는 사용자의 명시적 재시도로 남긴다.
        if (automatic && (!started || mutable.value.storageIssue || mutable.value.pending != null)) return
        if (!started || mutable.value.storageIssue) { resume(); return }
        if (mutable.value.pending != null) { recover(); return }
        val id = mutable.value.selectedId ?: return
        work(automatic = automatic) { user, stamp -> when (val response = repository.get(id)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                if (response.value.proposalId == id) apply(response.value, user, stamp) else showStage(ProposalDecisionStage.Unknown, invalid())
            }
            is ApiResult.Fail -> if (valid(stamp)) when {
                inaccessible(response.error) -> showStage(ProposalDecisionStage.Unavailable, response.error)
                automatic -> Unit   // 기다리는 화면은 그대로 두고 5초 뒤 다시 확인
                else -> showStage(ProposalDecisionStage.Unknown, response.error)
            }
        } }
    }
    private fun chatReady(p: ProposalRecord) = p.chatAvailable && (p.chatRoomId ?: 0) > 0

    /**
     * 서버가 확정한 제안 상태대로 이동한다.
     * - 수락 · 상대 기다림: 프로필에 "수락했어요"
     * - 서로 수락 · 방 있음: 바로 채팅 / 서로 수락인데 방을 쓸 수 없음(닫힘): 볼 수 없음 / 내가 거절: 목록
     * - 매칭 안 됨 · 기한 지남 · 알 수 없음: 결과 화면(본 결과는 수락 기록에서 지워 다시 안 보임)
     */
    private suspend fun apply(p: ProposalRecord, user: Long, stamp: Long) {
        if (!valid(stamp)) return
        when {
            // 서로 수락인데 방을 쓸 수 없음: 서버는 수락과 같은 트랜잭션에서 방을 만들므로 "아직"이 아니라 닫힌 방(동의 철회 · 나감 · 종료).
            // 기다리지 않고 프로필을 지운 채 끝낸다(서버도 이때 상대 프로필 · 사진을 막는다)
            p.status == "ACCEPTED" && p.myDecision == "ACCEPT" && !chatReady(p) -> {
                accepted.remove(user, setOf(p.proposalId))
                if (valid(stamp)) showStage(ProposalDecisionStage.Unavailable, selected = p.proposalId)
            }
            p.myDecision == "ACCEPT" && p.status == "WAITING_FOR_OTHER" -> {
                accepted.add(user, p.proposalId)
                if (!valid(stamp)) return
                val current = { s: ConnectedProposalState -> s.review?.current?.takeIf { it.context.proposalId == p.proposalId } }
                if (current(mutable.value) != null) mutable.update { s -> val now = current(s) ?: return@update s
                    s.copy(page = ProposalPageMode.Profile, result = null, error = null, review = s.review?.copy(
                        stage = ProposalReviewStage.Review, confirmation = null,
                        latest = now.copy(profile = now.profile.copy(myDecision = MyProposalDecision.AcceptedWaiting, canRespond = false)))) }
                else loadProfile(user, p.proposalId, stamp)
            }
            p.status == "ACCEPTED" && p.myDecision == "ACCEPT" -> {
                accepted.remove(user, setOf(p.proposalId))
                // 목록으로 돌려 두고 연다(제안 탭에 돌아와도 같은 채팅을 다시 열지 않게)
                if (valid(stamp)) mutable.update { it.copy(openChat = p.chatRoomId, page = ProposalPageMode.List, review = null, result = null, selectedId = null) }
            }
            p.myDecision == "DECLINE" -> readList(null, stamp)
            p.status == "PENDING" && p.myDecision == "NONE" -> loadProfile(user, p.proposalId, stamp)
            else -> {
                val stage = endedStage(p)
                if (stage != null) accepted.remove(user, setOf(p.proposalId))
                if (valid(stamp)) showStage(stage ?: ProposalDecisionStage.Unknown, selected = p.proposalId)
            }
        }
    }
    private fun showStage(stage: ProposalDecisionStage, error: AppError? = null, selected: Long? = null) =
        mutable.update { it.copy(page = ProposalPageMode.Result, review = null, error = error ?: it.error,
            selectedId = selected ?: it.selectedId, result = ProposalDecisionState(stage)) }
    fun chatOpened() = mutable.update { it.copy(openChat = null) }
    private fun decision(p: ProposalRecord) = if (p.myDecision == "ACCEPT") MyProposalDecision.AcceptedWaiting else MyProposalDecision.NotResponded
}

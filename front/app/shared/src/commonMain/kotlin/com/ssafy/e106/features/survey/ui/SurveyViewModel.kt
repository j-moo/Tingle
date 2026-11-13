package com.ssafy.e106.features.survey.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.survey.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement

enum class SurveyPage { Loading, Intro, Resume, Question, Review, Completed }
data class SurveyUiState(
    val owner: Session.SignedIn? = null,
    val page: SurveyPage = SurveyPage.Loading,
    val definition: Questionnaire? = null,
    val session: SurveySession? = null,
    val index: Int = 0,
    val draft: SurveyAnswer? = null,
    val saveState: SurveySaveState = SurveySaveState.Unanswered,
    val busy: Boolean = false,
    val error: AppError? = null,
    val recoveryRequired: Boolean = false,
    val previousBookmark: SurveyBookmark? = null,
    val pendingChange: Boolean = false,
    val pendingSourceId: Long? = null,
    val analysisReference: AnalysisReference? = null,
    val analysisResult: AnalysisResult? = null,
    val stageUnchanged: Boolean = false,
    val stageError: AppError? = null,
    val changingSession: Boolean = false,
    val exitRequested: Boolean = false,
    val returnedToMissing: Boolean = false,
    val serverDraftId: Long? = null,
    // null은 적용 여부 미확인이다. 분석 성공만 보고 매칭에 적용됐다고 표시하지 않는다.
    val analysisApplied: Boolean? = null,
    /** 아직 서버에 저장되지 않은 답(문항 → 보낼 값). 넣은 순서대로 뒤에서 보내고, 저장되면 뺀다 */
    val pending: Map<String, PendingAnswer> = emptyMap(),

) {
    val inputBlocked get() = busy || changingSession || exitRequested
    val question get() = definition?.questions?.getOrNull(index)
    val questionScreen get() = question?.screen(definition!!.questionCount, draft, saveState)
}

/** 보낼 답 하나. [value] 가 null 이면 답을 지우는 요청이다. 다른 회차로 바뀌면 [sessionId] 가 달라 버린다 */
data class PendingAnswer(val sessionId: Long, val value: JsonElement?)

class SurveyAccess(private val development: Boolean = false) {
    fun allowed(s: Session.SignedIn) = s.isReady || s.status == OnboardingStatus.SURVEY_REQUIRED ||
        (development && s.status == OnboardingStatus.KEYRING_REQUIRED)
}

/**
 * 답 저장은 화면을 막지 않는다: 고른 답을 줄 세워 두고 뒤에서 한 개씩 보내며, 실패하면 간격을 늘려 가며 계속 다시 보낸다.
 * 사용자는 저장을 기다리지 않고 다음 문항으로 넘어갈 수 있다. 제출은 줄이 다 비워진 뒤에만 한다.
 * 그 밖의 요청(불러오기 · 제출 · 분석)은 한 개씩 처리한다. 응답/지연 작업은 로그인 세대와 화면 수명에 묶는다.
 */
class SurveyViewModel(
    private val repository: SurveyRepository,
    private val sessions: SessionStore,
    private val cursor: SurveyCursorStore,
    private val access: SurveyAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(SurveyUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var sender: Job? = null
    private var poller: Job? = null
    private var generation = 0
    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { value ->
                work?.cancel(); sender?.cancel(); poller?.cancel(); generation++
                mutable.value = SurveyUiState(owner = value as? Session.SignedIn)
            }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.owner == sessions.state.value
    private fun launch(block: suspend (Long, Int) -> Unit) {
        val s = mutable.value; val owner = s.owner ?: return
        if (s.inputBlocked || owner != sessions.state.value || !access.allowed(owner)) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try { block(owner.userId, stamp) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid(stamp)) mutable.update { it.copy(error = AppError.Unknown(e), recoveryRequired = true) } }
            finally { if (valid(stamp)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun leave() {
        // 제출 · 회차 전환 같은 쓰기(busy)는 화면 이탈로 취소하지 않는다. 서버의 늦은 처리와 다음 입력이 겹칠 수 있다.
        // 답 저장 대기열은 busy 가 아니다: 나가면 못 보낸 답은 버리고(화면에 "저장 대기 N개"로 알린다) 늦은 응답은 generation 으로 무시한다
        if (mutable.value.busy) return
        // 실제 화면 이탈에서만 초기화한다. 재생성·ON_STOP에서는 호출하지 않는다.
        work?.cancel(); sender?.cancel(); poller?.cancel(); generation++
        mutable.value = SurveyUiState(owner = mutable.value.owner)
    }
    fun requestLeave() { mutable.update { it.copy(exitRequested = true) } }
    fun beginChange(): Boolean {
        val s = mutable.value
        // 보내지 못한 답이 남아 있는 동안에는 회차를 바꾸지 않는다(바꾸면 그 답을 버리게 된다)
        if (s.busy || s.changingSession || s.exitRequested || s.pending.isNotEmpty() || s.owner != sessions.state.value) return false
        // 두 ViewModel 사이의 화면 인계 동안 기존 모델의 새 조회·쓰기를 차단한다.
        generation++
        mutable.update { it.copy(changingSession = true) }
        return true
    }
    fun finishChange() {
        mutable.update { it.copy(changingSession = false) }
        load()
    }
    fun attach() {
        // 복귀할 때 보던 문항·검토 화면과 진행 중인 저장을 유지한다. 명시적 새로고침은 load를 쓴다.
        if (mutable.value.definition == null && !mutable.value.busy) load()
    }
    fun load() = launch { userId, stamp ->
        val id = cursor.read(userId) ?: mutable.value.session?.sessionId
        if (!valid(stamp)) return@launch
        if (id == null) {
            val pending = cursor.readChange(userId)
            val previous = cursor.readPrevious(userId)
            if (!valid(stamp)) return@launch
            mutable.update { it.copy(pendingChange = pending != null, pendingSourceId = pending?.sourceSessionId,
                previousBookmark = previous) }
            // 결과가 불명확한 전환은 기존 복구 흐름에서 먼저 확인한다. 서버 조회 실패를 새 가입으로 취급하지 않는다.
            if (pending != null) return@launch
            when (val response = repository.recovery(userId)) {
                is ApiResult.Fail -> { fail(response.error, stamp); return@launch }
                is ApiResult.Ok -> if (valid(stamp)) {
                    val recovery = response.value.checked()
                    mutable.update { it.copy(serverDraftId = recovery.surveySessionId) }
                    recovery.preferredSessionId()?.let { target ->
                        restoreAccountSession(userId, target, recovery, stamp)
                        return@launch
                    }
                } else return@launch
            }
            when (val result = repository.current()) {
                is ApiResult.Ok -> if (valid(stamp)) mutable.update {
                    it.copy(definition = result.value.checked(), page = SurveyPage.Intro, recoveryRequired = false)
                }
                is ApiResult.Fail -> fail(result.error, stamp)
            }
        } else {
            when (val result = repository.get(id)) {
                is ApiResult.Ok -> restore(result.value, stamp, expectedId = id)
                is ApiResult.Fail -> {
                    // 남의/삭제된 회차를 표시하지 않는다. 현재 설문은 다음 명시적 재조회에서 읽는다.
                    if (valid(stamp) && result.error is AppError.Server && result.error.status == 404) {
                        cursor.clearActive(userId, id)
                        val previous = cursor.readPrevious(userId)
                        val pending = cursor.readChange(userId)
                        if (valid(stamp)) mutable.update { SurveyUiState(owner = it.owner, error = result.error,
                            previousBookmark = previous, pendingChange = pending != null, pendingSourceId = pending?.sourceSessionId) }
                    } else fail(result.error, stamp)
                }
            }
        }
    }
    /** 로컬 기록이 사라졌을 때 서버가 확인한 회차·분석 ID를 저장하고 기존 설문 화면으로 복원한다. */
    private suspend fun restoreAccountSession(userId: Long, id: Long, recovery: SurveyAccountRecovery, stamp: Int) {
        when (val response = repository.get(id)) {
            is ApiResult.Fail -> fail(response.error, stamp)
            is ApiResult.Ok -> if (valid(stamp)) {
                require(response.value.sessionId == id)
                require(response.value.status == if (id == recovery.surveySessionId) "DRAFT" else "COMPLETED")
                cursor.write(userId, id)
                if (!valid(stamp)) return
                val receipt = recovery.latestAnalysis?.takeIf { it.sessionId == id }
                if (receipt != null) cursor.writeAnalysis(userId, AnalysisReference(id, receipt.resultId, receipt.analysisVersion))
                if (!valid(stamp)) return
                restore(response.value, stamp, id, recovery)
                // 제출까지 끝났고 아직 분석 요청 전일 때만 같은 회차로 접수한다. FAILED는 사용자가 재시도한다.
                if (valid(stamp) && !mutable.value.recoveryRequired && id == recovery.latestCompletedSessionId && receipt == null) {
                    requestAnalysisInside(userId, stamp)
                }
            }
        }
    }
    fun openServerDraft() {
        val s = mutable.value
        if (s.pendingChange || s.recoveryRequired || s.page != SurveyPage.Completed) return
        val draftId = s.serverDraftId ?: return
        launch { userId, stamp ->
            // 버튼을 보여준 뒤 다른 기기에서 제출했을 수 있으므로 서버의 최신 회차를 확인한다.
            when (val response = repository.recovery(userId)) {
                is ApiResult.Fail -> fail(response.error, stamp)
                is ApiResult.Ok -> if (valid(stamp)) {
                    val recovery = response.value.checked()
                    mutable.update { it.copy(serverDraftId = recovery.surveySessionId) }
                    if (recovery.surveySessionId == draftId) restoreAccountSession(userId, draftId, recovery, stamp)
                    else fail(AppError.Unknown(IllegalStateException("작성 중 회차가 변경됐어요. 서버 상태를 다시 확인해 주세요.")), stamp)
                }
            }
        }
    }
    fun start() {
        if (mutable.value.page != SurveyPage.Intro || mutable.value.recoveryRequired || mutable.value.error != null) return
        val version = mutable.value.definition?.manifest?.surveyVersion ?: return
        launch { userId, stamp ->
            when (val result = repository.start(version)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    require(result.value.manifest.surveyVersion == version && result.value.sessionId > 0)
                    cursor.write(userId, result.value.sessionId)
                    if (valid(stamp)) restore(result.value, stamp, result.value.sessionId)
                }
                is ApiResult.Fail -> fail(result.error, stamp)
            }
        }
    }
    private suspend fun restore(rawSession: SurveySession, stamp: Int, expectedId: Long, accountRecovery: SurveyAccountRecovery? = null) {
        if (!valid(stamp)) return
        require(rawSession.sessionId == expectedId)
        val before = mutable.value
        val retainPosition = before.session?.sessionId == expectedId && before.definition != null &&
            before.page in setOf(SurveyPage.Question, SurveyPage.Review)
        // ID는 본문을 받기 전에 보존해 문항 조회 실패 후에도 같은 회차를 복구한다.
        mutable.update { it.copy(session = rawSession, definition = null, page = SurveyPage.Loading) }
        when (val result = repository.questionnaire(expectedId)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val definition = result.value.checked()
                // 옛 저장 답 중 현재 회차 규칙에 맞지 않는 값은 화면에서 미응답으로 복구한다.
                val session = rawSession.checked(definition)
                // 같은 회차를 재조회해도 검토하던 위치는 유지하고, 답변 자체는 서버 값을 사용한다.
                val index = if (retainPosition) before.index.coerceIn(0, definition.questionCount - 1)
                    else definition.questions.indexOfFirst { it.questionId == session.nextQuestionId }.coerceAtLeast(0)
                mutable.update { it.copy(definition = definition, session = session, index = index,
                    page = when {
                        session.status == "COMPLETED" -> SurveyPage.Completed
                        retainPosition && before.page == SurveyPage.Review && session.nextQuestionId == null -> SurveyPage.Review
                        retainPosition -> SurveyPage.Question
                        session.answeredCount > 0 -> SurveyPage.Resume
                        else -> SurveyPage.Question
                    },
                    draft = null, saveState = SurveySaveState.Unanswered, recoveryRequired = false, error = null,
                    analysisReference = null, analysisResult = null, analysisApplied = null) }
                val owner = mutable.value.owner!!.userId
                val previous = cursor.readPrevious(owner)
                val pending = cursor.readChange(owner)
                if (!valid(stamp)) return
                mutable.update { it.copy(previousBookmark = previous, pendingChange = pending != null, pendingSourceId = pending?.sourceSessionId) }
                if (mutable.value.page == SurveyPage.Question) select(index)
                if (session.status == "COMPLETED") {
                    // 앱 재실행 뒤에도 공존하는 DRAFT와 최신 분석 버전을 다시 찾는다. 직전 회차의 선택은 바꾸지 않는다.
                    val recovery = accountRecovery ?: when (val response = repository.recovery(owner)) {
                        is ApiResult.Ok -> response.value.checked()
                        is ApiResult.Fail -> { fail(response.error, stamp); return }
                    }
                    if (!valid(stamp)) return
                    mutable.update { it.copy(serverDraftId = recovery.surveySessionId) }
                    val receipt = recovery.latestAnalysis?.takeIf { it.sessionId == session.sessionId }
                    val ref = if (recovery.latestCompletedSessionId == session.sessionId)
                        receipt?.let { AnalysisReference(it.sessionId, it.resultId, it.analysisVersion) }
                    else cursor.readAnalysis(owner)
                    if (!valid(stamp)) return
                    if (valid(stamp) && ref?.sessionId == session.sessionId) {
                        cursor.writeAnalysis(owner, ref)
                        if (!valid(stamp)) return
                        mutable.update { it.copy(analysisReference = ref) }
                        readAnalysis(ref, stamp)
                    }
                }
            }
            is ApiResult.Fail -> fail(result.error, stamp)
        }
    }
    private fun fail(error: AppError, stamp: Int) {
        if (valid(stamp)) mutable.update { it.copy(error = error, recoveryRequired = true) }
    }
    fun resume() {
        val s = mutable.value
        if (s.inputBlocked || s.recoveryRequired || s.page != SurveyPage.Resume) return
        if (s.session?.nextQuestionId == null) mutable.update { it.copy(page = SurveyPage.Review) }
        else select(s.index)
    }
    private fun select(index: Int) {
        val s = mutable.value; val q = s.definition?.questions?.getOrNull(index) ?: return
        // 아직 보내지 못한 답이 있으면 그것이 최신이다
        val queued = s.pending[q.questionId]
        val value = if (queued != null) queued.value else s.session?.answers?.find { it.questionId == q.questionId }?.value
        mutable.update { it.copy(page = SurveyPage.Question, index = index, draft = q.answer(value),
            saveState = if (value == null) SurveySaveState.Unanswered else SurveySaveState.Saved, error = null,
            returnedToMissing = false) }
    }
    fun previous() {
        val s = mutable.value
        if (!s.inputBlocked && !s.recoveryRequired && s.page == SurveyPage.Question && s.index > 0) select(s.index - 1)
    }
    fun next() {
        val s = mutable.value
        if (s.inputBlocked || s.recoveryRequired || s.page != SurveyPage.Question || s.questionScreen?.canContinue != true) return
        if (s.index + 1 < s.definition!!.questionCount) { select(s.index + 1); return }
        // 마지막 문항: 저장된 답 + 보내려고 줄 세운 답으로 빠진 문항을 찾는다(다른 기기에서 앞 답변이 삭제될 수 있다)
        fun answered(id: String) = s.pending[id]?.let { it.value != null } ?: (s.session?.answers?.any { it.questionId == id } == true)
        val missing = s.definition.questions.indexOfFirst { !answered(it.questionId) }
        if (missing < 0) mutable.update { it.copy(page = SurveyPage.Review) }
        else { select(missing); mutable.update { it.copy(returnedToMissing = true) } }
    }

    /** 완료 응답을 검증한 다음 분석을 요청한다. 제출 응답 유실은 GET 복구 후 사용자가 다시 진행한다. */
    fun submit() {
        val s = mutable.value
        // 보내지 못한 답이 남아 있으면 제출하지 않는다(답이 빠진 채 제출되면 분석이 "답 부족"으로 끝난다)
        if (s.pending.isNotEmpty()) return
        if (s.page != SurveyPage.Review || s.recoveryRequired || s.session?.status != "DRAFT" ||
            s.session.nextQuestionId != null || s.session.answeredCount != s.definition?.questionCount) return
        launch { userId, stamp ->
            when (val result = repository.complete(s.session.sessionId)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    val completed = result.value.checked(s.definition)
                    require(completed.sessionId == s.session.sessionId && completed.status == "COMPLETED")
                    mutable.update { it.copy(session = completed, page = SurveyPage.Completed, draft = null,
                        analysisReference = null, analysisResult = null) }
                    requestAnalysisInside(userId, stamp)
                    // 분석 접수까지 마친 뒤 단계 조회한다. 중간 단계 변경이 분석 요청을 취소하지 않게 한다.
                    if (valid(stamp) && mutable.value.analysisResult?.status != AnalysisStatus.SUCCEEDED) refreshStageInside(stamp)
                }
                is ApiResult.Fail -> fail(result.error, stamp)
            }
        }
    }
    fun requestAnalysis() {
        val s = mutable.value
        if (s.page != SurveyPage.Completed || s.session?.status != "COMPLETED" ||
            s.recoveryRequired || (s.analysisReference != null && s.analysisResult?.status != AnalysisStatus.FAILED)) return
        // 서버가 FAILED 재요청을 지원한다. 회차를 새로 만들거나 답변을 다시 제출하지 않는다.
        launch { userId, stamp -> requestAnalysisInside(userId, stamp) }
    }
    private suspend fun requestAnalysisInside(userId: Long, stamp: Int) {
        val sessionId = mutable.value.session!!.sessionId
        when (val result = repository.requestAnalysis(sessionId)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val receipt = result.value
                require(receipt.sessionId == sessionId && receipt.resultId > 0 && receipt.analysisVersion.isNotBlank())
                val ref = AnalysisReference(sessionId, receipt.resultId, receipt.analysisVersion)
                mutable.update { it.copy(analysisReference = ref, analysisResult = null) }
                cursor.writeAnalysis(userId, ref)
                if (valid(stamp)) readAnalysis(ref, stamp)
            }
            is ApiResult.Fail -> fail(result.error, stamp)
        }
    }
    fun refreshAnalysis() {
        val s = mutable.value
        if (s.page != SurveyPage.Completed || s.recoveryRequired) return
        val ref = s.analysisReference ?: return
        launch { _, stamp -> readAnalysis(ref, stamp) }
    }
    private suspend fun readAnalysis(ref: AnalysisReference, stamp: Int) {
        when (val response = repository.analysis(ref.resultId)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val result = response.value
                val s = mutable.value
                require(result.resultId == ref.resultId && result.sessionId == ref.sessionId)
                require(result.manifest.analysisVersion == ref.analysisVersion &&
                    result.manifest.surveyVersion == s.session!!.manifest.surveyVersion)
                require(result.missingQuestionIds.distinct().size == result.missingQuestionIds.size &&
                    result.missingQuestionIds.all { id -> s.definition!!.questions.any { it.questionId == id } })
                mutable.update { it.copy(analysisResult = result, recoveryRequired = false, error = null) }
                if (result.status == AnalysisStatus.SUCCEEDED) refreshStageInside(stamp)
                else if (result.status == AnalysisStatus.PENDING) watchAnalysis(ref, stamp)
            }
            is ApiResult.Fail -> fail(response.error, stamp)
        }
    }
    /**
     * 분석은 서버에서 몇 초 뒤에 끝난다. 결과가 PENDING 인 동안 뒤에서 다시 조회해, 끝나면 버튼을 누르지 않아도 화면이 넘어가게 한다.
     * 1분이 지나도 안 끝나면 멈추고 "분석 상태 확인" 버튼에 맡긴다. 화면을 나가거나 계정 · 회차가 바뀌면 멈춘다.
     */
    private fun watchAnalysis(ref: AnalysisReference, stamp: Int) {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            repeat(POLL_LIMIT) {
                delay(POLL_EVERY)
                val s = mutable.value
                if (!valid(stamp) || s.page != SurveyPage.Completed || s.analysisReference != ref ||
                    s.analysisResult?.status != AnalysisStatus.PENDING) return@launch
                if (s.busy) return@repeat                      // 사용자가 누른 조회 등 다른 작업이 도는 중이면 이번 차례는 건너뛴다
                // 조회 실패는 화면에 올리지 않고 다음 차례에 다시 본다(결과 조회는 여러 번 불러도 안전하다)
                val status = (repository.analysis(ref.resultId) as? ApiResult.Ok)?.value
                    ?.takeIf { it.resultId == ref.resultId && it.sessionId == ref.sessionId }?.status
                if (!valid(stamp)) return@launch
                // 끝난 결과는 버튼과 같은 경로로 반영한다(응답 검증 · 가입 단계 재조회 포함)
                if (status != null && status != AnalysisStatus.PENDING) { refreshAnalysis(); return@launch }
            }
        }
    }
    /** 성공한 분석도 가입 완료를 뜻하지 않는다. 실제 서버의 다음 단계를 다시 읽는다. */
    fun refreshStage() {
        if (mutable.value.analysisResult?.status != AnalysisStatus.SUCCEEDED || mutable.value.recoveryRequired) return
        launch { _, stamp -> refreshStageInside(stamp) }
    }
    private suspend fun refreshStageInside(stamp: Int) {
        mutable.update { it.copy(stageError = null, stageUnchanged = false) }
        if (mutable.value.analysisResult?.status == AnalysisStatus.SUCCEEDED) {
            val userId = mutable.value.owner?.userId ?: return
            when (val response = repository.recovery(userId)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    val recovery = response.value.checked()
                    mutable.update { it.copy(serverDraftId = recovery.surveySessionId,
                        analysisApplied = recovery.activeResultId == it.analysisResult?.resultId) }
                } else return
                is ApiResult.Fail -> {
                    if (valid(stamp)) mutable.update { it.copy(analysisApplied = null, stageError = response.error) }
                    return
                }
            }
        }
        when (val result = sessions.refreshOnboarding()) {
            is ApiResult.Ok -> if (valid(stamp)) mutable.update { it.copy(stageUnchanged = true) }
            // 단계 조회 실패는 이미 저장된 답변·분석 성공을 실패 상태로 되돌리지 않는다.
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(stageError = result.error) }
        }
    }

    fun openPrevious() {
        val s = mutable.value
        if (s.pendingChange) return
        val previous = s.previousBookmark ?: return
        val expectedId = s.session?.sessionId
        launch { userId, stamp ->
            when (val result = repository.get(previous.sessionId)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    require(result.value.sessionId == previous.sessionId)
                    if (cursor.activateIfCurrent(userId, expectedId, previous.sessionId)) {
                        if (valid(stamp)) restore(result.value, stamp, previous.sessionId)
                    } else if (valid(stamp)) {
                        // 다른 모델이 먼저 활성 회차를 옮겼으면 그 회차를 읽고 이전 조회 결과는 버린다.
                        val active = cursor.read(userId) ?: return@launch
                        when (val current = repository.get(active)) {
                            is ApiResult.Ok -> restore(current.value, stamp, active)
                            is ApiResult.Fail -> fail(current.error, stamp)
                        }
                    }
                }
                is ApiResult.Fail -> fail(result.error, stamp)
            }
        }
    }
    fun reviewAnswers() {
        val s = mutable.value
        if (!s.inputBlocked && !s.recoveryRequired && s.page == SurveyPage.Review) select(0)
    }
    fun answer(answer: SurveyAnswer) {
        val s = mutable.value
        if (s.inputBlocked || s.recoveryRequired || s.page != SurveyPage.Question || s.session?.status != "DRAFT") return
        val q = s.question ?: return
        // 마지막 복수 선택 해제는 빈 배열을 보내지 않고 DELETE로 미응답을 만든다.
        val delete = answer is SurveyAnswer.Choices && answer.keys.isEmpty()
        val value = if (delete) null else q.wire(answer) ?: return
        // 고르는 즉시 화면에는 답한 것으로 보이고("다음"이 켜진다) 저장은 뒤에서 한다
        mutable.update { it.copy(draft = if (delete) null else answer,
            saveState = if (delete) SurveySaveState.Unanswered else SurveySaveState.Saved,
            pending = it.pending - q.questionId + (q.questionId to PendingAnswer(s.session.sessionId, value))) }
        flush()
    }
    /** 줄 세운 답을 한 개씩 보낸다. 실패하면 간격을 늘려 가며(1초 → 최대 15초) 계속 다시 보낸다. 같은 답을 다시 보내도 결과는 같다 */
    private fun flush() {
        if (sender?.isActive == true) return
        val stamp = generation
        sender = viewModelScope.launch {
            var wait = FIRST_RETRY
            while (valid(stamp)) {
                val s = mutable.value
                val (questionId, item) = s.pending.entries.firstOrNull()?.toPair() ?: break
                val session = s.session; val definition = s.definition
                // 회차가 바뀌었으면 예전 회차의 답은 보내지 않는다
                if (session == null || definition == null || session.sessionId != item.sessionId || session.status != "DRAFT") {
                    mutable.update { it.copy(pending = it.pending - questionId) }; continue
                }
                val result: ApiResult<SurveySession> = try {
                    if (item.value == null) repository.delete(item.sessionId, questionId) else repository.save(item.sessionId, questionId, item.value)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { ApiResult.Fail(AppError.Unknown(e)) }
                if (!valid(stamp)) break
                val received = (result as? ApiResult.Ok)?.value?.takeIf { it.sessionId == item.sessionId }
                    ?.let { runCatching { it.checked(definition) }.getOrNull() }?.takeIf { it.status == "DRAFT" }
                if (received != null) {
                    wait = FIRST_RETRY
                    // 보내는 사이 같은 문항의 답을 바꿨으면 줄에 남겨 두고 새 값을 다시 보낸다
                    mutable.update { now -> now.copy(session = received,
                        pending = if (now.pending[questionId] == item) now.pending - questionId else now.pending) }
                } else {
                    val error = (result as? ApiResult.Fail)?.error
                    // 다시 보내도 소용없는 거절(회차가 이미 제출됨 · 값이 맞지 않음 등)은 반복하지 않고 서버 상태를 다시 불러오게 한다
                    // 성공 응답인데 이 회차의 작성 중 상태가 아니면(다른 기기에서 제출됨 등) 같은 요청을 반복해도 같다
                    if (result is ApiResult.Ok || error == AppError.SessionExpired ||
                        (error is AppError.Server && error.status in 400..499 && error.status != 408 && error.status != 429)) {
                        mutable.update { it.copy(pending = emptyMap(), error = error ?: AppError.Unknown(IllegalStateException("survey save response mismatch")), saveState = SurveySaveState.Failed, recoveryRequired = true) }
                        break
                    }
                    delay(wait); wait = (wait * 2).coerceAtMost(LAST_RETRY)
                }
            }
        }
    }
    private companion object { const val FIRST_RETRY = 1_000L; const val LAST_RETRY = 15_000L
        const val POLL_EVERY = 2_500L; const val POLL_LIMIT = 24 }
}

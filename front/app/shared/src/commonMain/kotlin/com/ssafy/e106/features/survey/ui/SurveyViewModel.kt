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

) {
    val inputBlocked get() = busy || changingSession || exitRequested
    val question get() = definition?.questions?.getOrNull(index)
    val questionScreen get() = question?.screen(definition!!.questionCount, draft, saveState)
}

class SurveyAccess(private val development: Boolean = false) {
    fun allowed(s: Session.SignedIn) = s.isReady || s.status == OnboardingStatus.SURVEY_REQUIRED ||
        (development && s.status == OnboardingStatus.KEYRING_REQUIRED)
}

/** 한 화면에서 저장 요청은 한 개씩 처리한다. 응답/지연 작업은 로그인 세대와 화면 수명에 묶는다. */
class SurveyViewModel(
    private val repository: SurveyRepository,
    private val sessions: SessionStore,
    private val cursor: SurveyCursorStore,
    private val access: SurveyAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(SurveyUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0
    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { value ->
                work?.cancel(); generation++
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
        // 진행 중인 쓰기를 화면 이탈로 취소하면 서버의 늦은 저장과 다음 입력이 겹칠 수 있다.
        if (mutable.value.busy) return
        // 실제 화면 이탈에서만 초기화한다. 재생성·ON_STOP에서는 호출하지 않는다.
        work?.cancel(); generation++
        mutable.value = SurveyUiState(owner = mutable.value.owner)
    }
    fun requestLeave() { mutable.update { it.copy(exitRequested = true) } }
    fun beginChange(): Boolean {
        val s = mutable.value
        if (s.busy || s.changingSession || s.exitRequested || s.owner != sessions.state.value) return false
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
    fun start() {
        if (mutable.value.page != SurveyPage.Intro) return
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
    private suspend fun restore(rawSession: SurveySession, stamp: Int, expectedId: Long) {
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
                    analysisReference = null, analysisResult = null) }
                val owner = mutable.value.owner!!.userId
                val previous = cursor.readPrevious(owner)
                val pending = cursor.readChange(owner)
                if (!valid(stamp)) return
                mutable.update { it.copy(previousBookmark = previous, pendingChange = pending != null, pendingSourceId = pending?.sourceSessionId) }
                if (mutable.value.page == SurveyPage.Question) select(index)
                if (session.status == "COMPLETED") {
                    val ref = cursor.readAnalysis(mutable.value.owner!!.userId)
                    if (valid(stamp) && ref?.sessionId == session.sessionId) {
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
        val value = s.session?.answers?.find { it.questionId == q.questionId }?.value
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
        if (s.index + 1 < s.definition!!.questionCount) select(s.index + 1)
        else if (s.session?.nextQuestionId == null && s.session?.answeredCount == s.definition.questionCount)
            mutable.update { it.copy(page = SurveyPage.Review) }
        else {
            // 다른 기기에서 앞 답변이 삭제될 수 있다. 최신 저장 응답의 첫 미응답으로 돌아간다.
            val missing = s.definition.questions.indexOfFirst { it.questionId == s.session?.nextQuestionId }
            if (missing >= 0) { select(missing); mutable.update { it.copy(returnedToMissing = true) } }
        }
    }

    /** 완료 응답을 검증한 다음 분석을 요청한다. 제출 응답 유실은 GET 복구 후 사용자가 다시 진행한다. */
    fun submit() {
        val s = mutable.value
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
            }
            is ApiResult.Fail -> fail(response.error, stamp)
        }
    }
    /** 성공한 분석도 가입 완료를 뜻하지 않는다. 실제 서버의 다음 단계를 다시 읽는다. */
    fun refreshStage() {
        if (mutable.value.analysisResult?.status != AnalysisStatus.SUCCEEDED || mutable.value.recoveryRequired) return
        launch { _, stamp -> refreshStageInside(stamp) }
    }
    private suspend fun refreshStageInside(stamp: Int) {
        mutable.update { it.copy(stageError = null, stageUnchanged = false) }
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
        mutable.update { it.copy(draft = if (delete) null else answer, saveState = SurveySaveState.Saving) }
        save(q.questionId, value)
    }
    private fun save(questionId: String, value: JsonElement?) = launch { _, stamp ->
        val s = mutable.value; val id = s.session!!.sessionId; val definition = s.definition!!
        val result = if (value == null) repository.delete(id, questionId) else repository.save(id, questionId, value)
        when (result) {
            is ApiResult.Ok -> if (valid(stamp)) {
                require(result.value.sessionId == id)
                val received = result.value.checked(definition)
                require(received.status == "DRAFT")
                val stored = received.answers.find { it.questionId == questionId }?.value
                require(if (value == null) stored == null else stored != null && sameAnswer(stored, value))
                mutable.update { it.copy(session = received, draft = it.question!!.answer(stored),
                    saveState = if (stored == null) SurveySaveState.Unanswered else SurveySaveState.Saved) }
            }
            is ApiResult.Fail -> if (valid(stamp)) mutable.update {
                // 응답 유실도 실패 표시 후 GET으로 확인한다. 쓰기를 자동 반복하지 않는다.
                it.copy(error = result.error, saveState = SurveySaveState.Failed, recoveryRequired = true)
            }
        }
    }
}

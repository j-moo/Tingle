package com.ssafy.e106.features.survey.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.survey.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SurveyChangePage { Loading, Retest, Migration, Current, Unsupported, PolicyChanged, Uncertain, Error, Done }
data class SurveyChangeState(
    val owner: Session.SignedIn? = null,
    val page: SurveyChangePage = SurveyChangePage.Loading,
    val busy: Boolean = false,
    val intent: SurveyChangeIntent? = null,
    val plan: SurveyMigrationPlan? = null,
    val error: AppError? = null,
    val exitRequested: Boolean = false,
)

/** 새 회차 선택/이관은 답변 입력과 분리한다. 확인 전에는 조회만 하고 기존 회차를 삭제하지 않는다. */
class SurveyChangeViewModel(
    private val repository: SurveyRepository, private val sessions: SessionStore,
    private val cursor: SurveyCursorStore, private val access: SurveyAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(SurveyChangeState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0
    private var sourceId: Long? = null
    private var kind = SurveyChangeKind.RETEST
    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect {
                // 계정 변경은 사용자 뒤로가기 대기와 달리 즉시 취소하고 이전 응답을 무효화한다.
                work?.cancel(); generation++
                mutable.value = SurveyChangeState(owner = it as? Session.SignedIn)
            }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.owner == sessions.state.value
    private fun launch(block: suspend (Long, Int) -> Unit) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.busy || mutable.value.exitRequested || owner != sessions.state.value || !access.allowed(owner)) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try { block(owner.userId, stamp) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(stamp)) mutable.update { it.copy(page = SurveyChangePage.Error, error = AppError.Unknown(e)) }
            }
            finally { if (valid(stamp)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun leave() {
        // 전환 요청 중 뒤로가기는 결과를 받은 다음 처리한다. 서버 처리 여부가 불명인 상태를 만들지 않는다.
        if (mutable.value.busy) return
        work?.cancel(); generation++
        mutable.value = SurveyChangeState(owner = mutable.value.owner)
    }
    fun requestLeave() { mutable.update { it.copy(exitRequested = true) } }
    fun attach(id: Long, requestedKind: SurveyChangeKind) {
        // 같은 화면의 재생성에서는 미리보기·전송 상태를 유지한다. 새 진입만 조회한다.
        if (!mutable.value.busy && mutable.value.page == SurveyChangePage.Loading) load(id, requestedKind)
    }
    fun load(id: Long, requestedKind: SurveyChangeKind) {
        sourceId = id; kind = requestedKind
        launch { userId, stamp ->
            val pending = cursor.readChange(userId)
            if (!valid(stamp)) return@launch
            if (pending != null) {
                sourceId = pending.sourceSessionId; kind = pending.kind
                mutable.update { it.copy(page = SurveyChangePage.Uncertain, intent = pending) }
            } else prepare(id, requestedKind, stamp)
        }
    }
    fun reload() {
        val id = sourceId ?: return
        launch { userId, stamp ->
            val pending = cursor.readChange(userId)
            if (!valid(stamp)) return@launch
            if (pending != null) mutable.update { it.copy(page = SurveyChangePage.Uncertain, intent = pending) }
            else prepare(id, kind, stamp)
        }
    }
    private suspend fun prepare(id: Long, requestedKind: SurveyChangeKind, stamp: Int) {
        mutable.update { it.copy(page = SurveyChangePage.Loading, intent = null, plan = null) }
        val source = when (val r = repository.get(id)) {
            is ApiResult.Ok -> r.value.also { require(it.sessionId == id) }
            is ApiResult.Fail -> { failure(r.error, stamp); return }
        }
        if (!valid(stamp)) return
        val target = when (val r = repository.current()) {
            is ApiResult.Ok -> r.value.checked()
            is ApiResult.Fail -> { failure(r.error, stamp); return }
        }
        if (!valid(stamp)) return
        val version = target.manifest.surveyVersion
        require(version.length in 1..30)
        if (requestedKind == SurveyChangeKind.RETEST) {
            require(source.status == "COMPLETED")
            mutable.update { it.copy(page = SurveyChangePage.Retest, intent = SurveyChangeIntent(id, version, requestedKind)) }
            return
        }
        if (source.manifest.surveyVersion == version) {
            mutable.update { it.copy(page = SurveyChangePage.Current) }; return
        }
        when (val r = repository.migrationPlan(id, version)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val p = r.value
                require(p.sourceSessionId == id && p.targetSurveyVersion == version)
                require(SurveyMigrationReview(id, version, p.policyHash, p.preservedQuestionIds,
                    p.reconfirmQuestionIds, p.incompatibleQuestionIds).valid)
                require(p.preservedQuestionIds.all { q -> source.answers.any { it.questionId == q } && target.questions.any { it.questionId == q } })
                require(p.reconfirmQuestionIds.all { q -> target.questions.any { it.questionId == q } })
                require(p.incompatibleQuestionIds.all { q -> source.answers.any { it.questionId == q } })
                mutable.update { it.copy(page = SurveyChangePage.Migration, plan = p,
                    intent = SurveyChangeIntent(id, version, requestedKind, p.policyHash)) }
            }
            is ApiResult.Fail -> if (valid(stamp)) {
                if ((r.error as? AppError.Server)?.code == "MIGRATION_UNSUPPORTED")
                    mutable.update { it.copy(page = SurveyChangePage.Unsupported) }
                else failure(r.error, stamp)
            }
        }
    }
    fun confirm() {
        val s = mutable.value
        if (s.page !in setOf(SurveyChangePage.Retest, SurveyChangePage.Migration, SurveyChangePage.Uncertain)) return
        val intent = s.intent ?: return
        launch { userId, stamp ->
            require(intent.sourceSessionId > 0 && intent.targetSurveyVersion.length in 1..30)
            if (intent.kind == SurveyChangeKind.MIGRATION) require(Regex("[0-9a-fA-F]{64}").matches(intent.policyHash.orEmpty()))
            cursor.writeChange(userId, intent)
            if (!valid(stamp)) return@launch
            // 전환 요청을 보내기 전에 복구 정보를 실제로 읽을 수 있어야 한다.
            check(cursor.readChange(userId) == intent)
            if (!valid(stamp)) return@launch
            mutable.update { it.copy(page = SurveyChangePage.Uncertain) }
            val result = if (intent.kind == SurveyChangeKind.RETEST) repository.start(intent.targetSurveyVersion)
                else repository.migrate(intent.sourceSessionId, intent.targetSurveyVersion, intent.policyHash!!)
            if (!valid(stamp)) return@launch
            when (result) {
                is ApiResult.Ok -> {
                    val session = result.value
                    require(session.sessionId > 0 && session.sessionId != intent.sourceSessionId && session.status == "DRAFT" &&
                        session.manifest.surveyVersion == intent.targetSurveyVersion)
                    // ID만 먼저 보존한다. 문항 검증 실패/화면 종료 뒤에도 이 회차를 GET으로 복원한다.
                    cursor.write(userId, session.sessionId)
                    if (!valid(stamp)) return@launch
                    check(cursor.read(userId) == session.sessionId)
                    cursor.writeChange(userId, null)
                    if (valid(stamp)) mutable.update { it.copy(page = SurveyChangePage.Done, error = null) }
                }
                is ApiResult.Fail -> {
                    val error = result.error
                    val server = error as? AppError.Server
                    val code = server?.code
                    // 확인된 BE 거절 응답만 전환 의도를 해제한다. 프록시 타임아웃·미확인 오류는 결과 불명으로 보존한다.
                    val rejected = server != null && server.status in 400..499 && code in setOf(
                        "MIGRATION_POLICY_CHANGED", "MIGRATION_UNSUPPORTED", "RESOURCE_NOT_FOUND",
                        "INVALID_REQUEST", "PROFILE_INCOMPLETE", "AUTH_REQUIRED", "FORBIDDEN", "ONBOARDING_REQUIRED")
                    if (rejected || error is AppError.OnboardingRequired || error is AppError.SessionExpired) {
                        cursor.writeChange(userId, null)
                        if (valid(stamp)) mutable.update { it.copy(
                            page = when(code) {
                                "MIGRATION_POLICY_CHANGED" -> SurveyChangePage.PolicyChanged
                                "MIGRATION_UNSUPPORTED" -> SurveyChangePage.Unsupported
                                else -> SurveyChangePage.Error
                            }, intent = null, plan = null, error = error) }
                    } else mutable.update { it.copy(page = SurveyChangePage.Uncertain, error = error) }
                }
            }
        }
    }
    private fun failure(error: AppError, stamp: Int) {
        if (valid(stamp)) mutable.update { it.copy(page = SurveyChangePage.Error, error = error) }
    }
}

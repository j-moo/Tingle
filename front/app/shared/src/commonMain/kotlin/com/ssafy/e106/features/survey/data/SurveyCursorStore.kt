package com.ssafy.e106.features.survey.data

import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.PrefKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** 응답 내용은 저장하지 않는다. 재진입 때 회차 ID만 읽고 서버에서 다시 조회한다. */
@Serializable data class SurveyCursor(val userId: Long, val sessionId: Long?, val analysis: AnalysisReference? = null,
    val previous: SurveyBookmark? = null, val pending: SurveyChangeIntent? = null)
interface SurveyCursorStore {
    suspend fun activateIfCurrent(userId: Long, expectedId: Long?, sessionId: Long): Boolean
    suspend fun clearActive(userId: Long, expectedId: Long)
    suspend fun readPrevious(userId: Long): SurveyBookmark?
    suspend fun readChange(userId: Long): SurveyChangeIntent?
    suspend fun writeChange(userId: Long, intent: SurveyChangeIntent?)
    suspend fun readAnalysis(userId: Long): AnalysisReference?
    suspend fun writeAnalysis(userId: Long, reference: AnalysisReference)
    suspend fun read(userId: Long): Long?
    suspend fun write(userId: Long, sessionId: Long?)
}
class PrefsSurveyCursorStore(private val prefs: AppPrefs) : SurveyCursorStore {
    // 같은 JSON 항목의 읽기-수정-쓰기를 직렬화해 분석 참조와 전환 의도가 서로 덮어쓰지 않게 한다.
    private val writes = Mutex()
    private val key = PrefKey("survey.active-session.v1", SurveyCursor.serializer())
    override suspend fun activateIfCurrent(userId: Long, expectedId: Long?, sessionId: Long): Boolean = writes.withLock {
        val old = entry(userId) ?: return@withLock false
        // 조회를 기다리는 동안 이관이 끝났다면 이전 조회가 새 활성 회차를 덮어쓰지 못하게 한다.
        if (old.sessionId != expectedId || old.pending != null) return@withLock false
        prefs.set(key, SurveyCursor(userId, sessionId,
            analysis = old.previous?.takeIf { it.sessionId == sessionId }?.analysis,
            previous = expectedId?.let { SurveyBookmark(it, old.analysis) }), forUser = userId)
        read(userId) == sessionId
    }
    override suspend fun read(userId: Long) = prefs.flow(key).first()?.takeIf { it.userId == userId }?.sessionId
    private suspend fun entry(userId: Long) = prefs.flow(key).first()?.takeIf { it.userId == userId }
    override suspend fun readPrevious(userId: Long) = entry(userId)?.previous
    override suspend fun readChange(userId: Long) = entry(userId)?.pending
    override suspend fun writeChange(userId: Long, intent: SurveyChangeIntent?) = writes.withLock {
        val old = entry(userId) ?: intent?.let { SurveyCursor(userId, it.sourceSessionId) } ?: return@withLock
        prefs.set(key, old.copy(pending = intent), forUser = userId)
    }
    override suspend fun write(userId: Long, sessionId: Long?) = writes.withLock {
        val old = entry(userId)
        val next = when {
            sessionId == null -> null
            old?.sessionId == sessionId -> old
            else -> SurveyCursor(userId, sessionId,
                analysis = old?.previous?.takeIf { it.sessionId == sessionId }?.analysis,
                previous = old?.sessionId?.let { SurveyBookmark(it, old.analysis) } ?: old?.previous, pending = old?.pending)
        }
        prefs.set(key, next, forUser = userId)
    }
    override suspend fun clearActive(userId: Long, expectedId: Long) = writes.withLock {
        val old = entry(userId) ?: return@withLock
        // 404가 된 활성 회차만 무효화한다. 직전 회차·전환 복구 정보는 별도로 남긴다.
        if (old.sessionId == expectedId) prefs.set(key, old.copy(sessionId = null, analysis = null), forUser = userId)
    }
    override suspend fun readAnalysis(userId: Long) = prefs.flow(key).first()?.takeIf { it.userId == userId }?.analysis
    override suspend fun writeAnalysis(userId: Long, reference: AnalysisReference) = writes.withLock {
        val previous = entry(userId)?.takeIf { it.sessionId == reference.sessionId } ?: return@withLock
        prefs.set(key, previous.copy(analysis = reference), forUser = userId)
    }
}

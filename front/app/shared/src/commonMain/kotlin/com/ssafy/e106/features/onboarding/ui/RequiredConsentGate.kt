package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.components.InlineError
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.features.auth.ui.SignupSheet
import com.ssafy.e106.features.profile.data.OptionalConsentRequest
import com.ssafy.e106.features.profile.data.OptionalConsentType
import com.ssafy.e106.features.profile.data.OptionalProfileRepository
import com.ssafy.e106.features.profile.ui.NoBackHandling
import com.ssafy.e106.resources.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 아직 받지 못한 필수 동의 하나 */
data class MissingConsent(val type: OptionalConsentType, val version: String, val title: String?, val reviewUrl: String?)

data class RequiredConsentState(
    val userId: Long? = null,
    /** 비어 있으면 통과. 확인하지 못했을 때(연결 끊김 등)도 비워 둔다 — 못 확인했다고 사용자를 내보내지 않는다 */
    val missing: List<MissingConsent> = emptyList(),
    val busy: Boolean = false, val error: AppError? = null,
)

/**
 * 필수 동의(프로필 사진 · 자기소개)가 빠진 계정을 찾아 한 번에 받는다.
 * 가입 때 동의 시트에서 필수로 받지만, 시트가 바뀌기 전에 가입을 시작했거나 나중에 철회한 계정은 빠져 있을 수 있다.
 */
class RequiredConsentViewModel(private val repository: OptionalProfileRepository, private val siteBase: String? = null) : ViewModel() {
    private val mutable = MutableStateFlow(RequiredConsentState())
    val state = mutable.asStateFlow()
    private var work: Job? = null

    /** 계정이 바뀌면 이전 계정의 결과를 버리고 다시 확인한다 */
    fun check(userId: Long) {
        if (mutable.value.userId == userId) return
        work?.cancel()
        mutable.value = RequiredConsentState(userId)
        work = viewModelScope.launch {
            val consents = repository.consents(userId) as? ApiResult.Ok ?: return@launch
            val lacking = consents.value.consents.filter { it.type.name in SignupRequiredConsents && !it.agreed }
            if (lacking.isEmpty()) return@launch
            // 전문 주소와 제목(서버가 주소를 안 주면 랜딩 페이지 주소로 채운다)
            val documents = (repository.documents() as? ApiResult.Ok)?.value?.agreements.orEmpty().withProfileDocuments(siteBase)
            // 읽을 수 없는 문서에 대한 동의를 기록하지 않는다: 같은 버전의 전문을 볼 수 있는 항목만 받는다(가입 시트 · 프로필 관리와 같은 규칙).
            // 하나라도 볼 수 없으면 조회 실패와 같이 막지 않는다. 사진 · 자기소개는 동의가 없으면 앱과 서버가 각각 막는다
            val items = lacking.map { record ->
                val doc = documents.firstOrNull { it.type == record.type.name && it.version == record.version && it.reviewUrl != null }
                    ?: return@launch
                MissingConsent(record.type, record.version, doc.signupTitle, doc.reviewUrl)
            }
            mutable.update { s -> if (s.userId != userId) s else s.copy(missing = items) }
        }
    }

    /** 빠진 필수 동의를 모두 저장한다. 하나라도 실패하면 남은 것만 다시 보여 준다 */
    fun agree() {
        val s = mutable.value
        val userId = s.userId ?: return
        if (s.busy || s.missing.isEmpty()) return
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            var failure: AppError? = null
            val left = s.missing.filter { item ->
                if (failure != null) return@filter true
                when (val result = repository.consent(userId, item.type, OptionalConsentRequest(true, item.version))) {
                    is ApiResult.Ok -> false
                    is ApiResult.Fail -> { failure = result.error; true }
                }
            }
            mutable.update { if (it.userId != userId) it else it.copy(missing = left, busy = false, error = failure) }
        }
    }
}

/**
 * 어느 화면에 있든 필수 동의가 빠져 있으면 시트를 얹어 받는다. 동의하지 않으면(✕ · 뒤로 가기 · "동의하지 않고 로그아웃") 로그아웃된다.
 * [content] 는 통과 전후 같은 자리에 둔다(자리가 바뀌면 아래 화면 상태가 처음부터 다시 만들어진다).
 */
@Composable
internal fun RequiredConsentGate(userId: Long, onLogout: () -> Unit, model: RequiredConsentViewModel = koinViewModel(), content: @Composable () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.check(userId) }
    val blocked = state.userId == userId && state.missing.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        Box(if (blocked) Modifier.clearAndSetSemantics {} else Modifier) { NoBackHandling(enabled = blocked) { content() } }
        if (blocked) SignupSheet(onClose = onLogout, background = {}) { RequiredConsentContent(state, model::agree, onLogout) }
    }
}

@Composable
internal fun RequiredConsentContent(state: RequiredConsentState, onAgree: () -> Unit, onLogout: () -> Unit) {
    val uri = LocalUriHandler.current
    // 다시 받는 목록이 바뀌면(일부만 저장됨) 체크를 처음부터
    var checked by remember(state.missing.map { it.type }) { mutableStateOf(emptySet<OptionalConsentType>()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(stringResource(Res.string.required_consent_title), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(Res.string.required_consent_help), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        state.missing.forEach { item ->
            val name = item.title ?: stringResource(if (item.type == OptionalConsentType.PROFILE_PHOTO) Res.string.required_consent_photo
                else Res.string.required_consent_introduction)
            AgreementRow(stringResource(Res.string.onboarding_required, name), item.type in checked, enabled = !state.busy,
                onToggle = { on -> checked = if (on) checked + item.type else checked - item.type },
                onRead = item.reviewUrl?.let { url -> { runCatching { uri.openUri(url) } } })
        }
        state.error?.let { InlineError(it.message, Modifier.padding(top = AppSpacing.Small)) }
        AppButton(stringResource(if (state.error != null) Res.string.state_retry else Res.string.required_consent_agree),
            Modifier.fillMaxWidth().padding(top = AppSpacing.Small), loading = state.busy,
            enabled = state.missing.all { it.type in checked }, onClick = onAgree)
        AppButton(stringResource(Res.string.required_consent_logout), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
            enabled = !state.busy, onClick = onLogout)
    }
}

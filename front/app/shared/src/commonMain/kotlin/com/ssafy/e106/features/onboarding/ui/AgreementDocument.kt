package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.components.WebDocument
import com.ssafy.e106.core.ui.components.WebDocumentLoad
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.agreement_document_browser
import com.ssafy.e106.resources.agreement_document_close
import com.ssafy.e106.resources.agreement_document_failed
import com.ssafy.e106.resources.agreement_document_retry
import org.jetbrains.compose.resources.stringResource

/** 약관 전문을 앱 화면 안에서 보여 준다(서버가 준 정적 페이지 주소). 닫기 · 시스템 뒤로 가기로 동의 화면에 돌아간다. 체크 상태는 그대로 */
@Composable
internal fun AgreementDocument(title: String, url: String, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    var attempt by remember(url) { mutableIntStateOf(0) }
    var load by remember(url, attempt) { mutableStateOf(WebDocumentLoad.Loading) }
    ProfileBackHandler(enabled = true, onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = onClose, backText = stringResource(Res.string.agreement_document_close))
            Box(Modifier.weight(1f).fillMaxSize()) {
                key(url, attempt) {   // 다시 시도 = 웹 화면을 새로 만든다
                    WebDocument(url, onLoad = { load = it }, onExternal = { runCatching { uri.openUri(it) } }, modifier = Modifier.fillMaxSize())
                }
                when (load) {
                    WebDocumentLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = AppColors.TextPrimary)
                    WebDocumentLoad.Failed -> Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
                        Column(Modifier.fillMaxSize().padding(AppSpacing.Section), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(Res.string.agreement_document_failed), style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                            AppButton(stringResource(Res.string.agreement_document_retry), onClick = { attempt++ })
                            TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(Res.string.agreement_document_browser)) }
                        }
                    }
                    WebDocumentLoad.Loaded -> Unit
                }
            }
        }
    }
}

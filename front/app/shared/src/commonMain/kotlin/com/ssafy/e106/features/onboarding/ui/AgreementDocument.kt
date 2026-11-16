package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.components.PullDownRefreshBox
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.components.SkeletonGroup
import com.ssafy.e106.core.ui.components.StateKind
import com.ssafy.e106.core.ui.components.StateView
import com.ssafy.e106.core.ui.components.WebDocument
import com.ssafy.e106.core.ui.components.WebDocumentLoad
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.agreement_document_browser
import com.ssafy.e106.resources.agreement_document_close
import com.ssafy.e106.resources.agreement_document_failed
import org.jetbrains.compose.resources.stringResource

/** 약관 전문을 앱 화면 안에서 보여 준다(서버가 준 정적 페이지 주소). 닫기 · 시스템 뒤로 가기로 동의 화면에 돌아간다. 체크 상태는 그대로 */
@Composable
internal fun AgreementDocument(title: String, url: String, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    var attempt by remember(url) { mutableIntStateOf(0) }
    var load by remember(url, attempt) { mutableStateOf(WebDocumentLoad.Loading) }
    ProfileBackHandler(enabled = true, onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = onClose, backText = stringResource(Res.string.agreement_document_close))
            Box(Modifier.weight(1f).fillMaxSize()) {
                key(url, attempt) {   // 다시 시도 = 웹 화면을 새로 만든다
                    WebDocument(url, onLoad = { load = it }, onExternal = { runCatching { uri.openUri(it) } }, modifier = Modifier.fillMaxSize())
                }
                when (load) {
                    // 받는 중: 웹 화면을 불투명하게 덮고(비치지 않게) 글 줄 자리에 뼈대
                    WebDocumentLoad.Loading -> Surface(Modifier.fillMaxSize(), color = AppColors.Sheet) {
                        SkeletonGroup(Modifier.padding(screenPaddingValues())) {
                            SkeletonBone(Modifier.fillMaxWidth(0.5f), height = 24.dp)
                            repeat(8) { SkeletonBone(Modifier.fillMaxWidth(if (it % 3 == 2) 0.6f else 1f)) }
                        }
                    }
                    // 못 받음: 웹 화면은 오류 종류를 알려 주지 않아 "알 수 없는 오류" 모양으로. 당기거나 작은 "다시 시도"로 다시 받는다
                    WebDocumentLoad.Failed -> Surface(Modifier.fillMaxSize(), color = AppColors.Sheet) {
                        PullDownRefreshBox(false, { attempt++ }, Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                StateView(StateKind.Unknown, stringResource(Res.string.agreement_document_failed), onRetry = { attempt++ })
                                TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(Res.string.agreement_document_browser)) }
                            }
                        }
                    }
                    WebDocumentLoad.Loaded -> Unit
                }
            }
        }
    }
}

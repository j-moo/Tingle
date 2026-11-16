package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.core.ui.components.TagLabel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 당겨서 새로고침의 고리 상태(돌고 있는지, 당겼을 때 할 일). 화면의 busy 는 저장 중에도 켜져서 그대로 넘기면 저장할 때마다 화면이 내려온다.
 * 그래서 당긴 뒤부터 바쁜 일이 끝날 때까지만 돈다. 요청이 시작되지 않았어도(이미 다른 작업 중) 고리가 남지 않게 잠깐 뒤에 접는다.
 */
@Composable
internal fun pullRefresh(busy: Boolean, onRefresh: () -> Unit): Pair<Boolean, () -> Unit> {
    var pulled by remember { mutableStateOf(false) }
    val nowBusy by rememberUpdatedState(busy)
    LaunchedEffect(pulled) { if (pulled) { delay(400); snapshotFlow { nowBusy }.first { !it }; pulled = false } }
    return pulled to { pulled = true; onRefresh() }
}

/** 뼈대 화면의 틀: 위쪽 줄과 아래 버튼(눌리지 않음)은 실제와 같게 두고, 가운데에 항목 이름과 값 자리를 둔다 */
@Composable
internal fun ProfileSkeletonFrame(title: String, button: String?, onBack: (() -> Unit)? = null, spacing: Dp = AppSpacing.Large,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            }
            // 버튼 이름이 없으면(값을 다 채워야 버튼이 나오는 화면) 버튼 자리를 두지 않는다
            if (button != null) AppButton(button, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), enabled = false, onClick = {})
        }
    }
}

/** 뼈대의 항목 하나: 실제 항목 이름 + 값 자리(기본은 입력 칸 크기) */
@Composable
internal fun BoneField(label: StringResource, bone: @Composable ColumnScope.() -> Unit = { SkeletonBone(height = AppSpacing.InputMinHeight, shape = AppShapes.Control) }) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        TagLabel(stringResource(label))
        bone()
    }
}

/** 사진 자리(정사각형 144) */
@Composable
internal fun ColumnScope.PhotoBone() = SkeletonBone(Modifier.align(Alignment.CenterHorizontally), width = 144.dp, height = 144.dp, shape = AppShapes.Card)
/** 사진 칸 뼈대: 실제 사진 칸처럼 좌우를 채운 정사각형 */
@Composable
internal fun ColumnScope.PhotoBoneWide() = BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
    SkeletonBone(height = maxWidth, shape = AppShapes.Card)
}

package com.ssafy.e106.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.state_loading
import com.ssafy.e106.resources.state_refresh
import org.jetbrains.compose.resources.stringResource

/**
 * 당겨서 새로고침: 내용 전체가 손가락을 따라 내려오고, 위쪽 빈자리에 고리가 드러난다(놓으면 고리가 돈다).
 * [content] 는 세로로 스크롤할 수 있어야 당김이 잡힌다. 당길 수 없는 사용자를 위해 같은 동작을 접근성 동작으로도 둔다.
 * 내용이 상태 바 밑까지 깔린 화면은 [indicatorInsets] 로 고리를 상태 바 아래로 내린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullDownRefreshBox(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    indicatorInsets: WindowInsets = WindowInsets(0), content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    val travel = with(LocalDensity.current) { PullTravel.toPx() }
    val label = stringResource(Res.string.state_refresh)
    PullToRefreshBox(refreshing, onRefresh,
        modifier.semantics { customActions = listOf(CustomAccessibilityAction(label) { onRefresh(); true }) },
        state, contentAlignment = Alignment.TopCenter,
        indicator = {
            val pulled = state.distanceFraction.coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(indicatorInsets).fillMaxWidth().height(PullTravel).graphicsLayer { alpha = pulled }
                .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                if (refreshing) CircularProgressIndicator(Modifier.size(AppSizes.Icon), color = AppColors.TextPrimary,
                    trackColor = AppColors.BorderSubtle, strokeWidth = PullStroke)
                else CircularProgressIndicator(progress = { pulled }, Modifier.size(AppSizes.Icon), color = AppColors.TextPrimary,
                    trackColor = AppColors.BorderSubtle, strokeWidth = PullStroke)
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = state.distanceFraction.coerceIn(0f, 1f) * travel },
            contentAlignment = Alignment.TopCenter, content = content)
    }
}

/**
 * 뼈대 묶음: 불러오는 중에 실제 화면과 같은 자리에 회색 조각을 둔다. 화면 리더에는 조각 대신 [label] 한 문장만 읽힌다.
 * 화면 틀(제목 · 항목 이름 · 아래 버튼)은 이 밖에서 실제와 같게 그리고, 값이 들어갈 자리만 이 안에 둔다.
 */
@Composable
fun SkeletonGroup(modifier: Modifier = Modifier, label: String = stringResource(Res.string.state_loading),
    spacing: Dp = AppSpacing.Large, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** 회색 조각 하나. 너비를 주지 않으면 가로를 채운다. 천천히 옅어졌다 진해진다 */
@Composable
fun SkeletonBone(modifier: Modifier = Modifier, width: Dp? = null, height: Dp = 16.dp, shape: Shape = AppShapes.Small) {
    val pulse by rememberInfiniteTransition().animateFloat(1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Box((if (width != null) modifier.width(width) else modifier.fillMaxWidth()).height(height)
        .graphicsLayer { alpha = pulse }.background(AppColors.SurfaceMuted, shape))
}

/** 목록 한 줄 뼈대: 동그란 사진 자리 + 글 두 줄 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, avatar: Dp = 48.dp) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        SkeletonBone(width = avatar, height = avatar, shape = CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            SkeletonBone(Modifier.fillMaxWidth(0.45f), height = 16.dp)
            SkeletonBone(Modifier.fillMaxWidth(0.75f), height = 12.dp)
        }
    }
}

/** 입력 칸 뼈대: 이름 자리 + 입력 상자 자리 */
@Composable
fun SkeletonField(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        SkeletonBone(Modifier.fillMaxWidth(0.3f), height = 14.dp)
        SkeletonBone(height = AppSpacing.InputMinHeight, shape = AppShapes.Control)
    }
}

private val PullTravel = 64.dp
private val PullStroke = 2.5.dp

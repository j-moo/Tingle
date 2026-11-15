package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.button_previous
import org.jetbrains.compose.resources.stringResource

/**
 * 화면 상단 바. 왼쪽 이전, 가운데 제목, 오른쪽 보조 동작(글자). Figma 01 Tingle/ServiceHeader.
 * 최소 높이 64, 글자 확대 시 늘어난다. [onBack] 을 주면 이전 글자가 보인다. 아이콘 동작이 필요해지면 슬롯으로 바꾼다.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backText: String = stringResource(Res.string.button_previous),
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    /** 글자 동작 대신 오른쪽에 둘 아이콘 버튼 등 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = AppSizes.TopBarMinHeight).padding(horizontal = AppSpacing.Section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        if (onBack != null) BarAction(backText, onBack)
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionText != null && onAction != null) BarAction(actionText, onAction)
        trailing?.invoke()
    }
}

/** 글자 동작. minimumInteractiveComponentSize 가 가로·세로 48dp 터치 영역을 보장한다. */
@Composable
private fun BarAction(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary,
        modifier = Modifier.clip(AppShapes.Small).clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize().wrapContentHeight().padding(horizontal = AppSpacing.Small),
    )
}

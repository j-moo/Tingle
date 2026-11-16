package com.ssafy.e106.core.ui.components

import com.ssafy.e106.core.ui.theme.frost
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors

/** 회색 바탕 위 같은 폭 탭. 고른 쪽만 흰 바탕. 선택 index 는 호출 화면이 소유한다(희망 나이 · 받은 제안) */
@Composable
fun AppSegmentedTabs(labels: List<String>, selected: Int?, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().frost(RoundedCornerShape(12.dp)).background(AppColors.SurfaceMuted).padding(3.dp).selectableGroup()) {
        labels.forEachIndexed { i, label ->
            val on = selected == i
            Box(Modifier.weight(1f).heightIn(min = 44.dp)
                .then(if (on) Modifier.background(AppColors.Sheet, RoundedCornerShape(9.dp)) else Modifier)
                .clip(RoundedCornerShape(9.dp))
                .selectable(on, enabled = enabled, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = if (on) AppColors.TextPrimary else AppColors.TextSecondary)
            }
        }
    }
}

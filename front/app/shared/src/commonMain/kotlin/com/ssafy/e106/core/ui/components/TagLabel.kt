package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing

private const val TagSeparator = " · "

/** "닉네임 · 필수" → ("닉네임", "필수"). 꼬리표는 맨 끝의 두 글자 이하 낱말(필수 · 선택)만 인정한다. "희망 나이 · 매칭 설정"처럼 긴 것은 그대로 둔다 */
internal fun splitTag(text: String): Pair<String, String?> {
    val at = text.lastIndexOf(TagSeparator)
    val tag = if (at > 0) text.substring(at + TagSeparator.length) else null
    return if (tag != null && tag.length in 1..2) text.substring(0, at) to tag else text to null
}

/** 꼬리표를 뗀 이름만(입력 칸 안 라벨 · 창 제목처럼 박스를 그릴 수 없는 곳) */
fun String.withoutTag(): String = splitTag(this).first

/** 작은 회색 박스(필수 · 선택) */
@Composable
fun LabelTag(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.background(AppColors.SurfaceMuted, AppShapes.Small).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary, maxLines = 1)
}

/**
 * 항목 이름 + 오른쪽 작은 회색 박스. "이름 · 필수" / "이름 · 선택" 문구를 받아 꼬리표만 박스로 그린다(꼬리표가 없으면 이름만).
 * 화면 리더에는 "닉네임 필수"처럼 이어서 읽힌다.
 */
@Composable
fun TagLabel(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = AppColors.TextPrimary, fontWeight: FontWeight? = null, heading: Boolean = false) {
    val (name, tag) = splitTag(text)
    Row(modifier.semantics(mergeDescendants = true) { if (heading) heading() }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(name, Modifier.weight(1f, fill = false), style = style, color = color, fontWeight = fontWeight)
        if (tag != null) LabelTag(tag)
    }
}

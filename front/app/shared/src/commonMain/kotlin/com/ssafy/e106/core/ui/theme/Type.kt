package com.ssafy.e106.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.noto_sans_kr
import org.jetbrains.compose.resources.Font

@Composable
fun appTypography(): Typography {
    val font = FontFamily(
        Font(Res.font.noto_sans_kr, FontWeight.Normal),
        Font(Res.font.noto_sans_kr, FontWeight.Medium),
        Font(Res.font.noto_sans_kr, FontWeight.Bold),
    )
    return Typography(
        titleLarge = TextStyle(fontFamily = font, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        bodyLarge = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodySmall = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 18.sp),
    )
}

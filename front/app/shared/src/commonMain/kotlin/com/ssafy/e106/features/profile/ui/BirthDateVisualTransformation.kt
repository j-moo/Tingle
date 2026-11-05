package com.ssafy.e106.features.profile.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** 값은 숫자 8자리로 유지하고, 표시와 커서 위치만 YYYY-MM-DD로 바꾼다. */
internal object BirthDateVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val display = buildString {
            digits.forEachIndexed { index, char ->
                if (index == 4 || index == 6) append('-')
                append(char)
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val position = offset.coerceIn(0, digits.length)
                return position + (if (position >= 4 && digits.length > 4) 1 else 0) +
                    (if (position >= 6 && digits.length > 6) 1 else 0)
            }

            override fun transformedToOriginal(offset: Int): Int {
                val position = offset.coerceIn(0, display.length)
                return (position - (if (position > 4) 1 else 0) -
                    (if (position > 7) 1 else 0)).coerceIn(0, digits.length)
            }
        }
        return TransformedText(AnnotatedString(display), mapping)
    }
}

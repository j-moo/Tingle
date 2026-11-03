@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("CAST_NEVER_SUCCEEDS")

package com.ssafy.e106.features.profile

import kotlinx.cinterop.useContents
import platform.Foundation.*

internal actual fun profileNfc(value: String): String =
    (value as NSString).precomposedStringWithCanonicalMapping

internal actual fun profileGraphemeCount(value: String): Int {
    val text = value as NSString
    var index = 0uL
    var count = 0
    while (index < text.length) {
        index = text.rangeOfComposedCharacterSequenceAtIndex(index).useContents { location + length }
        count++
    }
    return count
}

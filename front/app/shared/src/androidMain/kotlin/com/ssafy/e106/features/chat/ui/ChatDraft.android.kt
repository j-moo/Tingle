package com.ssafy.e106.features.chat.ui

import java.util.regex.Pattern

private val chatGraphemes = Pattern.compile("\\X")
internal actual fun chatGraphemeCount(value: String): Int {
    val matcher = chatGraphemes.matcher(value)
    var count = 0
    while (matcher.find()) count++
    return count
}

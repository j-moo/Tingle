package com.ssafy.e106.features.profile

import java.text.Normalizer
import java.util.regex.Pattern

internal actual fun profileNfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

// Android Pattern의 Unicode 확장 문자소. UTF-16 length/code point 수로 대체하지 않는다.
private val graphemePattern = Pattern.compile("\\X")

internal actual fun profileGraphemeCount(value: String): Int {
    val matcher = graphemePattern.matcher(value)
    var count = 0
    while (matcher.find()) count++
    return count
}

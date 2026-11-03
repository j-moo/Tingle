package com.ssafy.e106.features.profile

/** 서버 ProfileRules와 동일하게 NFC 후 앞뒤 U+0020만 제거한다. 입력 중 IME 조합에는 적용하지 않는다. */
internal fun normalizeProfileNickname(value: String): String = profileNfc(value).trim(' ')

/** 줄바꿈만 통일하고 공백뿐인 소개는 미입력으로 취급한다. 일반 소개의 앞뒤 공백은 보존한다. */
internal fun normalizeProfileIntroduction(value: String): String? {
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
    // Java String.isBlank와 맞춤: NBSP/figure space/narrow NBSP는 빈 입력으로 취급하지 않는다.
    return normalized.takeUnless { text -> text.all {
        it.isWhitespace() && it != '\u00A0' && it != '\u2007' && it != '\u202F'
    } }
}

internal expect fun profileNfc(value: String): String
internal expect fun profileGraphemeCount(value: String): Int

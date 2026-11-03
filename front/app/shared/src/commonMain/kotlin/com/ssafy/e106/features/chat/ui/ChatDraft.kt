package com.ssafy.e106.features.chat.ui

enum class ChatDraftError { Blank, Characters, Bytes, InvalidText }

/** 입력 중인 원문은 바꾸지 않고, 전송에 쓸 LF 정규화 값을 별도로 보관한다. */
data class ChatDraft(val normalized: String, val graphemes: Int, val utf8Bytes: Int, val error: ChatDraftError?) {
    val valid: Boolean get() = error == null

    companion object {
        fun inspect(raw: String): ChatDraft {
            val text = raw.replace("\r\n", "\n").replace('\r', '\n')
            val bytes = text.encodeToByteArray().size
            val count = chatGraphemeCount(text)
            var invalid = false
            var index = 0
            while (index < text.length) {
                val c = text[index]
                when {
                    c in '\uD800'..'\uDBFF' -> {
                        if (index + 1 < text.length && text[index + 1] in '\uDC00'..'\uDFFF') index++
                        else invalid = true
                    }
                    c in '\uDC00'..'\uDFFF' -> invalid = true
                    (c.code in 0..31 || c.code in 127..159) && c != '\n' && c != '\t' -> invalid = true
                }
                index++
            }
            val error = when {
                invalid -> ChatDraftError.InvalidText
                text.isBlank() -> ChatDraftError.Blank
                bytes > 65536 -> ChatDraftError.Bytes
                count > 1000 -> ChatDraftError.Characters
                else -> null
            }
            return ChatDraft(text, count, bytes, error)
        }
    }
}

internal expect fun chatGraphemeCount(value: String): Int

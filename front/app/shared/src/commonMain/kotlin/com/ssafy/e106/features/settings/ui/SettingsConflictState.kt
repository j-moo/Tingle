package com.ssafy.e106.features.settings.ui

/** 화면용 값. ETag와 기기 동기화용 settingsVersion은 서로 대신할 수 없다. */
sealed interface ConflictValues {
    val valid: Boolean
    data class Age(val minimum: Int? = null, val maximum: Int? = null, val any: Boolean = false,
        val selected: Boolean = true) : ConflictValues {
        override val valid: Boolean get() = selected && if (any) minimum == null && maximum == null
            else minimum != null && minimum >= 19 && (maximum == null || maximum >= minimum)
        val unselected: Boolean get() = !selected && !any && minimum == null && maximum == null
    }
    data class Matching(val enabled: Boolean, val photoRequired: Boolean) : ConflictValues {
        override val valid: Boolean get() = true
    }
}

enum class ConflictStage { Changed, VersionMissing, Loading, LoadFailed, Review, Saving, SaveFailed, OutcomeUnknown }

data class ConflictSnapshot<T : ConflictValues>(val ownerId: Long, val values: T, val etag: String)

sealed interface ConflictAction<out T : ConflictValues> {
    data object Reload : ConflictAction<Nothing>
    data object Back : ConflictAction<Nothing>
    data class UseLatest<T : ConflictValues>(val snapshot: ConflictSnapshot<T>) : ConflictAction<T>
    data class SaveReviewed<T : ConflictValues>(val snapshot: ConflictSnapshot<T>, val intended: T) : ConflictAction<T>
}

/** 순수 화면 상태. Reload/SaveReviewed의 성공은 실제 호출부만 판정한다. */
data class SettingsConflictState<T : ConflictValues>(
    val ownerId: Long,
    val intended: T,
    val stage: ConflictStage = ConflictStage.Changed,
    val latest: ConflictSnapshot<T>? = null,
) {
    val busy: Boolean get() = stage == ConflictStage.Loading || stage == ConflictStage.Saving
    val reviewed: ConflictSnapshot<T>? get() = latest?.takeIf {
        (stage == ConflictStage.Review || stage == ConflictStage.SaveFailed) && ownerId > 0 &&
            it.ownerId == ownerId && (it.values.valid || (it.values as? ConflictValues.Age)?.unselected == true) &&
            it.values::class == intended::class &&
            it.etag.length > 2 && it.etag.first() == '"' && it.etag.last() == '"' &&
            it.etag.substring(1, it.etag.lastIndex).all { c -> c != '"' && c.code >= 0x21 && c.code != 0x7f }
    }
    val saveAction: ConflictAction.SaveReviewed<T>? get() = reviewed?.takeIf {
        intended.valid && it.values != intended
    }?.let { ConflictAction.SaveReviewed(it, intended) }

    /** 412/428/응답 유실이면 예전 GET 결과로 저장하지 않도록 재확인을 요구한다. */
    fun requireReload(reason: ConflictStage) = copy(stage = reason, latest = null)
}

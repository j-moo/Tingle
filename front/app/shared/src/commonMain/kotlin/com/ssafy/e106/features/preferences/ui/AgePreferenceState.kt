package com.ssafy.e106.features.preferences.ui

import com.ssafy.e106.features.settings.ui.ConflictValues
import com.ssafy.e106.features.settings.ui.SettingsConflictState

enum class AgePreferenceMode { Any, Range }

/** 화면 입력 상태. 서버 저장·동기화 여부는 여기서 만들지 않는다. */
data class AgePreferenceState(
    val mode: AgePreferenceMode? = null,
    val minimumAge: String = "",
    val maximumAge: String = "",
    val noUpperLimit: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    // 표시 눈금만 관리한다. 입력 가능한 나이의 상한이 아니다.
    val sliderUpperAge: Int = 60,
    val conflict: SettingsConflictState<ConflictValues.Age>? = null,
) {
    val minimumValue: Int? get() = minimumAge.ageValue()
    val maximumValue: Int? get() = maximumAge.ageValue()
    val minimumInvalid: Boolean get() = minimumAge.isNotEmpty() && minimumValue == null
    val maximumInvalid: Boolean get() = !noUpperLimit && maximumAge.isNotEmpty() && maximumValue == null
    val reversed: Boolean get() = !noUpperLimit && minimumValue != null && maximumValue != null &&
        minimumValue!! > maximumValue!!
    val validRange: Boolean get() = minimumValue != null && (noUpperLimit || (maximumValue != null && !reversed))
    val canContinue: Boolean get() = !saving && conflict == null && when (mode) {
        AgePreferenceMode.Any -> true
        AgePreferenceMode.Range -> validRange
        null -> false
    }
    val sliderEnd: Int get() = maxOf(60, sliderUpperAge, minimumValue ?: 19,
        if (noUpperLimit) 19 else maximumValue ?: 19)

    fun changeMinimum(value: String) = copy(minimumAge = value, saveFailed = false,
        sliderUpperAge = maxOf(sliderEnd, value.ageValue() ?: 19))

    fun changeMaximum(value: String) = copy(maximumAge = value, saveFailed = false,
        sliderUpperAge = maxOf(sliderEnd, value.ageValue() ?: 19))

    fun changeRange(minimum: Int, maximum: Int) = copy(
        minimumAge = minimum.toString(), maximumAge = maximum.toString(),
        sliderUpperAge = sliderEnd, saveFailed = false,
    )
}

private fun String.ageValue(): Int? = takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it >= 19 }

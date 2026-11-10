package com.ssafy.e106.features.preferences.ui

import com.ssafy.e106.features.settings.ui.ConflictValues
import com.ssafy.e106.features.settings.ui.SettingsConflictState

enum class AgePreferenceMode { Any, Range }

internal const val MIN_AGE = 19
/** 막대 끝. 여기까지 밀면 "제한 없음"(60세+). 숫자 칸에는 그보다 큰 나이도 쓸 수 있다 */
internal const val AGE_SLIDER_TOP = 60

/** 화면 입력 상태. 서버 저장·동기화 여부는 여기서 만들지 않는다. */
data class AgePreferenceState(
    val mode: AgePreferenceMode? = null,
    val minimumAge: String = "",
    val maximumAge: String = "",
    val noUpperLimit: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
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

    /** 범위를 처음 고르면 화면에 보이는 그대로(만 19세 이상 · 제한 없음)를 값으로 채운다. 안 채우면 막대는 고른 듯 보이는데 저장이 안 된다 */
    fun selectMode(value: AgePreferenceMode) = copy(mode = value, saveFailed = false).let {
        if (value == AgePreferenceMode.Range && it.minimumAge.isEmpty()) it.copy(minimumAge = MIN_AGE.toString(), noUpperLimit = it.maximumAge.isEmpty()) else it
    }

    /** 최대 칸이 비어 있으면 제한 없음 그대로 */
    fun changeMinimum(value: String) = copy(minimumAge = value, noUpperLimit = noUpperLimit || maximumAge.isEmpty(), saveFailed = false)

    /** 최대 칸을 비우면 제한 없음 */
    fun changeMaximum(value: String) = copy(maximumAge = value, noUpperLimit = value.isEmpty(), saveFailed = false)

    /** 막대: 최대 손잡이를 끝(60세+)까지 밀면 제한 없음 */
    fun changeSlider(minimum: Int, maximum: Int) = copy(minimumAge = minimum.toString(), saveFailed = false,
        maximumAge = if (maximum >= AGE_SLIDER_TOP) "" else maximum.toString(), noUpperLimit = maximum >= AGE_SLIDER_TOP)
}

private fun String.ageValue(): Int? = takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it >= MIN_AGE }

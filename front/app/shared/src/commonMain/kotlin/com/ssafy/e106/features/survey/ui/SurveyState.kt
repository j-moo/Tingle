package com.ssafy.e106.features.survey.ui

/** UI-only values. API response parsing and survey/session ownership belong to the caller. */
data class SurveyOption(val key: String, val label: String)

sealed interface SurveyAnswer {
    data class Choices(val keys: Set<String>) : SurveyAnswer
    data object Unknown : SurveyAnswer
    data object Any : SurveyAnswer
}

enum class SurveySaveState { Unanswered, Saving, Saved, Failed }

data class SurveyQuestionState(
    val questionId: String,
    val ordinal: Int,
    val total: Int,
    val text: String,
    val options: List<SurveyOption>,
    val maxChoices: Int = 1,
    val allowUnknown: Boolean = false,
    val allowAny: Boolean = false,
    val answer: SurveyAnswer? = null,
    val saveState: SurveySaveState = SurveySaveState.Unanswered,
) {
    val hasAnswer: Boolean get() = when (val value = answer) {
        is SurveyAnswer.Choices -> value.keys.size in 1..maxChoices &&
            value.keys.all { key -> options.any { it.key == key } }
        SurveyAnswer.Unknown -> allowUnknown
        SurveyAnswer.Any -> allowAny
        null -> false
    }
    val canContinue: Boolean get() = hasAnswer && saveState == SurveySaveState.Saved
}

enum class SurveyFlowStage { Resume, ReadyToSubmit, Submitting, SubmitFailed, Calculating, CalculationFailed, Complete }

data class SurveyFlowState(
    val stage: SurveyFlowStage,
    val savedCount: Int = 15,
    val total: Int = 100,
    val hasPreviousResult: Boolean = false,
    val recovery: SurveyRecoveryState? = null,
)

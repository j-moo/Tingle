package com.ssafy.e106.features.survey.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** 질문지 보기 값 · 문구로 화면 모양을 고르는 규칙(SIS-Q-1.1-100 의 다섯 가지) */
class SurveyAnswerStyleTest {
    private fun q(keys: List<String>, labels: List<String> = keys, max: Int = 1) =
        SurveyQuestionState("Q", 1, 100, "질문", keys.zip(labels) { k, l -> SurveyOption(k, l) }, maxChoices = max)

    private val five = (1..5).map { "$it" }

    @Test fun standardFiveStepWordingIsScale() = assertEquals(SurveyAnswerStyle.Scale, q(five, STANDARD_SCALE).answerStyle())

    @Test fun fiveStepWithOwnSentencesIsOrdinal() =
        assertEquals(SurveyAnswerStyle.Ordinal, q(five, listOf("기다려요", "살펴요", "달라요", "먼저 표현", "분명하게")).answerStyle())

    @Test fun lowMidHighIsLevel() = assertEquals(SurveyAnswerStyle.Level, q(listOf("low", "mid", "high")).answerStyle())

    @Test fun otherSingleChoiceIsTiles() = assertEquals(SurveyAnswerStyle.Single, q(listOf("explain", "apologize", "change")).answerStyle())

    @Test fun moreThanOneChoiceIsChips() {
        assertEquals(SurveyAnswerStyle.Multi, q(listOf("rest", "talk", "culture"), max = 2).answerStyle())
        assertEquals(SurveyAnswerStyle.Multi, q(five, STANDARD_SCALE, max = 2).answerStyle())   // 개수 규칙이 값 모양보다 먼저
    }
}

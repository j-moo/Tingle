package com.ssafy.e106.features.survey.ui

import com.ssafy.e106.features.survey.data.*
import kotlinx.serialization.json.*

private val supported = setOf("oneOf", "enum", "const", "type", "items", "minItems", "maxItems", "uniqueItems")
// 공개 저장 경로의 SurveyAnswerValidator는 숫자·문자열과 그 배열만 받는다.
// 저장 엔티티가 BOOLEAN/NULL을 표현할 수 있어도 현재 API에 없는 보기 입력을 열지 않는다.
internal fun sameAnswer(a: JsonElement, b: JsonElement): Boolean {
    if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
        val x = a.doubleOrNull; val y = b.doubleOrNull
        return x != null && y != null && x.isFinite() && y.isFinite() && x == y
    }
    return a == b
}
internal fun matches(schema: JsonObject, value: JsonElement): Boolean {
    require(schema.isNotEmpty() && schema.keys.all { it in supported })
    schema["oneOf"]?.let { if (it.jsonArray.count { branch -> matches(branch.jsonObject, value) } != 1) return false }
    schema["const"]?.let { if (!sameAnswer(it, value)) return false }
    schema["enum"]?.let { if (it.jsonArray.none { option -> sameAnswer(option, value) }) return false }
    if (schema.keys.any { it in setOf("type", "items", "minItems", "maxItems", "uniqueItems") }) {
        require(schema["type"]?.jsonPrimitive?.content == "array")
        if (value !is JsonArray) return false
        if (value.size < (schema["minItems"]?.jsonPrimitive?.int ?: 0) ||
            value.size > (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) return false
        if (schema["uniqueItems"]?.jsonPrimitive?.boolean == true && value.indices.any { i -> value.take(i).any { sameAnswer(it, value[i]) } }) return false
        schema["items"]?.let { item -> if (value.any { !matches(item.jsonObject, it) }) return false }
    }
    return true
}
private fun WireQuestion.arrayRule(): JsonObject? =
    (responseSchema["oneOf"]?.jsonArray?.map { it.jsonObject } ?: listOf(responseSchema))
        .singleOrNull { it["type"]?.jsonPrimitive?.content == "array" }

internal fun WireQuestion.answer(value: JsonElement?): SurveyAnswer? { return when {
    value == null -> null
    value == JsonPrimitive("unknown") && allowUnknown -> SurveyAnswer.Unknown
    value == JsonPrimitive("any") && allowAny -> SurveyAnswer.Any
    else -> {
        val values = if (value is JsonArray) value.toList() else listOf(value)
        val keys = values.map { v -> options.singleOrNull { sameAnswer(it.value, v) }?.value?.toString() ?: return null }
        SurveyAnswer.Choices(keys.toSet())
    }
} }
internal fun WireQuestion.wire(answer: SurveyAnswer): JsonElement? {
    val value = when (answer) {
        SurveyAnswer.Unknown -> if (allowUnknown) JsonPrimitive("unknown") else return null
        SurveyAnswer.Any -> if (allowAny) JsonPrimitive("any") else return null
        is SurveyAnswer.Choices -> {
            if (!answer.keys.all { key -> options.any { it.value.toString() == key } }) return null
            val selected = options.filter { it.value.toString() in answer.keys }.map { it.value }
            if (arrayRule() != null) JsonArray(selected) else selected.singleOrNull() ?: return null
        }
    }
    return value.takeIf { matches(responseSchema, it) }
}
internal fun WireQuestion.screen(total: Int, value: SurveyAnswer?, save: SurveySaveState) = SurveyQuestionState(
    questionId, ordinal, total, text, options.map { SurveyOption(it.value.toString(), it.label) },
    maxChoices = arrayRule()?.get("maxItems")?.jsonPrimitive?.int ?: 1,
    allowUnknown = allowUnknown && matches(responseSchema, JsonPrimitive("unknown")),
    allowAny = allowAny && matches(responseSchema, JsonPrimitive("any")), answer = value, saveState = save,
)
internal fun Questionnaire.checked(): Questionnaire {
    require(questionCount > 0 && questionCount == questions.size && manifest.surveyVersion.isNotBlank())
    require(questions.map { it.questionId }.distinct().size == questionCount)
    require(questions.map { it.ordinal }.sorted() == (1..questionCount).toList())
    questions.forEach { q ->
        require(q.questionId.isNotBlank() && q.text.isNotBlank() && q.options.isNotEmpty())
        require(q.options.map { it.value.toString() }.distinct().size == q.options.size)
        q.options.forEach { option ->
            require(option.label.isNotBlank())
            require(q.wire(SurveyAnswer.Choices(setOf(option.value.toString()))) != null)
        }
        q.screen(questionCount, null, SurveySaveState.Unanswered)
    }
    return copy(questions = questions.sortedBy { it.ordinal })
}
internal fun SurveySession.checked(definition: Questionnaire): SurveySession {
    require(sessionId > 0 && status in setOf("DRAFT", "COMPLETED") && manifest == definition.manifest)
    require(questionCount == definition.questionCount && answeredCount == answers.size)
    require(answers.map { it.questionId }.distinct().size == answers.size)
    require(answers.all { a -> definition.questions.any { it.questionId == a.questionId } })
    val next = definition.questions.firstOrNull { q -> answers.none { it.questionId == q.questionId } }?.questionId
    require(nextQuestionId == next && (status != "COMPLETED" || next == null))
    // 조회는 옛 답도 포함하지만 제출은 유효한 답만 센다. 원본 응답 구조 검증 후 화면용 진행률만 다시 계산한다.
    // 숫자 문자열 등을 자동 변환하거나 서버에서 삭제하지 않고 해당 문항을 사용자가 다시 답하게 한다.
    val validAnswers = answers.filter { a -> matches(definition.questions.single { it.questionId == a.questionId }.responseSchema, a.value) }
    val unanswered = definition.questions.firstOrNull { q -> validAnswers.none { it.questionId == q.questionId } }?.questionId
    return copy(answers = validAnswers, answeredCount = validAnswers.size, nextQuestionId = unanswered)
}

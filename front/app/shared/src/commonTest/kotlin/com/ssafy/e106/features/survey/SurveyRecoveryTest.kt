package com.ssafy.e106.features.survey

import com.ssafy.e106.features.survey.ui.*
import kotlin.test.*

class SurveyRecoveryTest {
    private val plan = SurveyMigrationReview(1, "new-survey", "a".repeat(64), listOf("Q1"), listOf("Q2"), listOf("Q3"))
    private fun state(stage: SurveyRecoveryStage) = SurveyRecoveryState(stage, 1, migration = plan)

    @Test fun completedInsufficientNeverOffersToEditOldSessionOrRetryCalculation() {
        val incomplete = state(SurveyRecoveryStage.AnswersIncomplete)
        assertEquals(SurveyRecoveryAction.RefreshSession, incomplete.primaryAction)
        val insufficient = state(SurveyRecoveryStage.Insufficient).copy(missingQuestions = listOf(SurveyMissingQuestion("Q1")))
        assertEquals(SurveyRecoveryAction.ReviewRetest, insufficient.primaryAction)
        assertEquals(SurveyRecoveryAction.ReloadResult, insufficient.copy(missingQuestions = emptyList()).primaryAction)
    }
    @Test fun unverifiedOrWrongSessionPlanCannotBeConfirmed() {
        val state = state(SurveyRecoveryStage.MigrationReview)
        for (invalid in listOf(plan.copy(sourceSessionId = 2), plan.copy(policyHash = ""),
            plan.copy(targetSurveyVersion = ""), plan.copy(reconfirmQuestionIds = listOf("Q1")))) {
            assertEquals(SurveyRecoveryAction.ReloadMigrationPlan, state.copy(migration = invalid).primaryAction)
        }
        assertNull(state.copy(sourceSessionId = null).primaryAction)
    }
    @Test fun changedPolicyRequiresReviewAndConfirmationCarriesTheReviewedHash() {
        assertEquals(SurveyRecoveryAction.ReloadMigrationPlan, state(SurveyRecoveryStage.MigrationPolicyChanged).primaryAction)
        val latest = plan.copy(policyHash = "b".repeat(64))
        val action = assertIs<SurveyRecoveryAction.ConfirmMigration>(state(SurveyRecoveryStage.MigrationReview).copy(migration = latest).primaryAction)
        assertEquals(latest, action.plan)
        assertEquals(SurveyRecoveryAction.CheckCurrentSurvey, state(SurveyRecoveryStage.MigrationUnsupported).primaryAction)
    }
    @Test fun unknownOutcomeRetainsExactOriginalRequestAndBusyCannotSubmit() {
        val action = assertIs<SurveyRecoveryAction.CheckMigrationOutcome>(state(SurveyRecoveryStage.MigrationOutcomeUnknown).primaryAction)
        assertEquals(plan, action.plan)
        for (stage in listOf(SurveyRecoveryStage.MigrationLoading, SurveyRecoveryStage.Migrating)) assertNull(state(stage).primaryAction)
    }
    @Test fun retestRequiresKnownVersionAndMissingIdsAreNotFabricated() {
        val confirmation = state(SurveyRecoveryStage.RetestConfirmation)
        assertEquals(SurveyRecoveryAction.CheckCurrentSurvey, confirmation.primaryAction)
        assertEquals(SurveyRecoveryAction.StartOrResumeRetest("new-survey"), confirmation.copy(retestSurveyVersion = "new-survey").primaryAction)
        val missing = state(SurveyRecoveryStage.Insufficient).copy(missingQuestions = listOf(SurveyMissingQuestion(""), SurveyMissingQuestion("Q1"), SurveyMissingQuestion("Q1")))
        assertEquals(listOf(SurveyMissingQuestion("Q1")), missing.visibleMissing)
    }
}

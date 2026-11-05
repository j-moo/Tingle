package com.ssafy.e106.features.account.ui

import com.ssafy.e106.features.onboarding.data.isPublishedHttpsUrl

enum class HelpGuideKind { Terms, PrivacyPolicy, Support, Correction }
enum class HelpGuideStage { Loading, Ready, Unavailable, LoadFailed, OpenFailed }
enum class HelpGuideAction { Back, Reload, Open }

/** Read-only presentation. Opening a document/channel never implies consent or a submitted request. */
data class HelpGuideState(
    val kind: HelpGuideKind,
    val stage: HelpGuideStage = HelpGuideStage.Unavailable,
    val destinationUrl: String? = null,
    val documentTitle: String? = null,
    val version: String? = null,
) {
    val openUrl: String? get() = destinationUrl?.takeIf {
        stage in listOf(HelpGuideStage.Ready, HelpGuideStage.OpenFailed) && isPublishedHttpsUrl(it)
    }
    val isInquiry get() = kind == HelpGuideKind.Support || kind == HelpGuideKind.Correction
}

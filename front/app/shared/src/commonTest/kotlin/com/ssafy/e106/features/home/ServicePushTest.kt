package com.ssafy.e106.features.home
import com.ssafy.e106.features.home.ui.*
import kotlin.test.*
class ServicePushTest {
    @Test fun pushNavigationNeverInterruptsDraftOrSafetyConfirmation() {
        assertTrue(canOpenServicePush(ServicePage.Home)); assertTrue(canOpenServicePush(ServicePage.Menu))
        listOf(ServicePage.Chats,ServicePage.Safety,ServicePage.Report,ServicePage.AiConsent,ServicePage.Profile,ServicePage.Survey,ServicePage.Settings)
            .forEach { assertFalse(canOpenServicePush(it),it.name) }
    }
}

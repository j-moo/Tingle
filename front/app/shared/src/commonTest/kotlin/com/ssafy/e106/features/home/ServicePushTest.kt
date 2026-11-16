package com.ssafy.e106.features.home
import com.ssafy.e106.features.home.ui.*
import kotlin.test.*
class ServicePushTest {
    @Test fun pushNavigationNeverInterruptsDraftOrSafetyConfirmation() {
        assertTrue(canOpenServicePush(ServicePage.Home)); assertTrue(canOpenServicePush(ServicePage.Menu))
        assertTrue(canOpenServicePush(ServicePage.Notifications))   // 알림 모음을 보다가 시스템 알림을 눌러도 이동
        listOf(ServicePage.Chats,ServicePage.Safety,ServicePage.Report,ServicePage.AiConsent,ServicePage.Profile,ServicePage.Survey,ServicePage.Settings)
            .forEach { assertFalse(canOpenServicePush(it),it.name) }
    }
}

package com.ssafy.e106.features.keyring

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 테스트 APK 전용. 실제 계정이나 BLE 기기를 생성하지 않고 화면의 재조회 수명만 검증한다. */
class OwnedKeyringsTestActivity : ComponentActivity() {
    companion object {
        val reads = AtomicInteger()
        val recordEntries = AtomicInteger()
        val registrationEntries = AtomicInteger()
        val syncEntries = AtomicInteger()
        val selectedDevice = AtomicInteger()
        val metricFixture = MutableStateFlow<Pair<Long?, OwnedKeyringLiveStatus?>>(null to null)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(clazz: Class<T>): T = OwnedKeyringsViewModel(
                object : OwnedKeyringRepository {
                    override suspend fun list(): ApiResult<List<OwnedKeyring>> {
                        reads.incrementAndGet()
                        return ApiResult.Ok(if (intent.getBooleanExtra("empty", false)) emptyList()
                            else listOf(OwnedKeyring(7, "READY", "1.0")))
                    }
                }, TestSession(OnboardingStatus.READY)) as T
        })[OwnedKeyringsViewModel::class.java]
        setContent { AppTheme {
            val session = Session.SignedIn(1, OnboardingStatus.READY, false)
            if (intent.getBooleanExtra("syncPicker", false)) {
                OwnedKeyringSyncScreen(
                    OwnedKeyringsState(session, listOf(
                        OwnedKeyring(7, "READY", "1.0", "XIAO-first"),
                        OwnedKeyring(13, "READY", "1.0", "XIAO-second"),
                    )),
                    selectedId = null, aliases = mapOf(13L to "시험 키링"), availability = BleAvailability.Ready,
                    link = BleProbeState(), syncing = false, onBack = {}, onRefresh = {},
                    onSelect = { selectedDevice.set(it.deviceId.toInt()) },
                ) {}
            } else if (intent.getBooleanExtra("metrics", false)) {
                val (id, snapshot) = metricFixture.collectAsStateWithLifecycle().value
                OwnedKeyringsScreen(OwnedKeyringsState(session, listOf(OwnedKeyring(7, "READY", "1.0"))),
                    {}, {}, {}, connectedDeviceId = id, liveStatus = snapshot, onRefreshStatus = {})
            } else ConnectedOwnedKeyringsRoute(session, {}, model, onReset = {},
                recoveryLinks = {}, onConnectForRecords = { recordEntries.incrementAndGet() },
                onRegister = { registrationEntries.incrementAndGet() },
                onSync = { syncEntries.incrementAndGet() })
        } }
    }
}

class OwnedKeyringsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun oneReadOnEntryThenFreshReadAfterRotationAndBackground() {
        OwnedKeyringsTestActivity.reads.set(0)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            OwnedKeyringsTestActivity::class.java)).use { scenario ->
            compose.onNodeWithText("펌웨어 1.0").assertExists()
            assertEquals(1, OwnedKeyringsTestActivity.reads.get())
            scenario.recreate(); compose.waitForIdle()
            assertEquals(2, OwnedKeyringsTestActivity.reads.get())
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED); compose.waitForIdle()
            assertEquals(3, OwnedKeyringsTestActivity.reads.get())
            compose.onNodeWithText("내 키링 1").assertExists()
        }
    }

    /** 목록에서 수동으로 진입할 수 있고, 목록을 보기만 할 때는 연결 화면을 자동으로 열지 않는다. */
    @Test fun ownedListOpensRecordConnectionOnlyOnTap() {
        OwnedKeyringsTestActivity.recordEntries.set(0)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            OwnedKeyringsTestActivity::class.java)).use {
            compose.onNodeWithText("내 키링 1").assertExists()
            assertEquals(0, OwnedKeyringsTestActivity.recordEntries.get())
            compose.onNodeWithText("만남 기록 전송을 위해 키링 연결").performClick()
            assertEquals(1, OwnedKeyringsTestActivity.recordEntries.get())
        }
    }

    /** 마지막 키링을 초기화한 뒤 빈 목록에서도 새 등록 화면으로 들어갈 수 있다. */
    @Test fun emptyOwnedListOffersRegistration() {
        OwnedKeyringsTestActivity.registrationEntries.set(0)
        val intent = Intent(InstrumentationRegistry.getInstrumentation().context, OwnedKeyringsTestActivity::class.java)
            .putExtra("empty", true)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(intent).use {
            compose.onNodeWithText("등록된 키링이 없어요.").assertExists()
            compose.onNodeWithText("키링 설정 동기화").assertDoesNotExist()
            compose.onNodeWithText("만남 기록 전송을 위해 키링 연결").assertDoesNotExist()
            compose.onNodeWithText("키링 등록하기").performClick()
            assertEquals(1, OwnedKeyringsTestActivity.registrationEntries.get())
        }
    }

    /** 등록된 기기가 있으면 기록 전송 메뉴와 별도로 설정 동기화에 진입할 수 있다. */
    @Test fun ownedListOpensSettingsSync() {
        OwnedKeyringsTestActivity.syncEntries.set(0)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            OwnedKeyringsTestActivity::class.java)).use {
            compose.onNodeWithText("키링 설정 동기화").performClick()
            assertEquals(1, OwnedKeyringsTestActivity.syncEntries.get())
        }
    }

    @Test fun settingsSyncShowsRegisteredDevicesAndPassesSelectedId() {
        OwnedKeyringsTestActivity.selectedDevice.set(0)
        val intent = Intent(InstrumentationRegistry.getInstrumentation().context, OwnedKeyringsTestActivity::class.java)
            .putExtra("syncPicker", true)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(intent).use {
            compose.onNodeWithText("내 키링 1").assertExists()
            compose.onNodeWithText("시험 키링").assertExists()
            compose.onNodeWithText("주변 키링을 찾고 있어요").assertDoesNotExist()
            assertEquals(0, OwnedKeyringsTestActivity.selectedDevice.get())
            compose.onNodeWithText("시험 키링").performClick()
            assertEquals(13, OwnedKeyringsTestActivity.selectedDevice.get())
        }
    }

    @Test fun connectedCardShowsZeroUnknownFailureAndDropsValuesOnDisconnect() {
        val status = OwnedKeyringLiveStatus(1, 7, 1, loading = false,
            metrics = BleDeviceStatus(0, null, null, 0))
        OwnedKeyringsTestActivity.metricFixture.value = 7L to status
        val intent = Intent(InstrumentationRegistry.getInstrumentation().context, OwnedKeyringsTestActivity::class.java)
            .putExtra("metrics", true)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(intent).use {
            compose.onNodeWithText("배터리 0%").assertExists()
            compose.onNodeWithText("미전송 기록 0건").assertExists()
            OwnedKeyringsTestActivity.metricFixture.value = 7L to status.copy(metrics = null)
            compose.onNodeWithText("배터리 미확인").assertExists()
            compose.onNodeWithText("미전송 기록 미확인").assertExists()
            OwnedKeyringsTestActivity.metricFixture.value = 7L to status.copy(metrics = null, failed = true)
            compose.onNodeWithText("키링 상태를 불러오지 못했어요.").assertExists()
            OwnedKeyringsTestActivity.metricFixture.value = null to status
            compose.onNodeWithText("연결하면 상태를 확인할 수 있어요.").assertExists()
            compose.onNodeWithText("배터리 0%").assertDoesNotExist()
        }
    }
}

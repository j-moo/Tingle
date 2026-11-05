package com.ssafy.e106.core.push

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 실제 SharedPreferences 로 설치 ID · binding 저장. 디스크 쓰기 실패(commit = false)는 예외로 알려야 등록기가 성공으로 보지 않는다 */
@RunWith(AndroidJUnit4::class)
class AndroidPushDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("tingle.push.test", Context.MODE_PRIVATE)

    @After
    fun clear() { prefs.edit().clear().commit() }

    /** 디스크 쓰기가 실패하는 SharedPreferences 흉내. 값은 실제 파일(메모리)에 들어가고 commit 만 false */
    private class FailingCommit(private val real: SharedPreferences) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = Editor(real.edit())
        private class Editor(private val real: SharedPreferences.Editor) : SharedPreferences.Editor by real {
            override fun putString(key: String?, value: String?) = apply { real.putString(key, value) }
            override fun putLong(key: String?, value: Long) = apply { real.putLong(key, value) }
            override fun remove(key: String?) = apply { real.remove(key) }
            override fun commit(): Boolean { real.apply(); return false }
        }
    }

    @Test
    fun installationIdIsKeptAcrossInstances() = runTest {
        val id = AndroidPushDevice(prefs).installationId()
        assertEquals(id, AndroidPushDevice(prefs).installationId())
        assertEquals(36, id.length)   // UUID
    }

    @Test
    fun bindingSavedAndCleared() = runTest {
        val device = AndroidPushDevice(prefs)
        assertNull(device.binding())
        device.saveBinding(PushBinding(7, "b7"))
        val read = AndroidPushDevice(prefs).binding()
        assertEquals(7L, read?.userId)
        assertEquals("b7", read?.id)
        device.saveBinding(null)
        assertNull(device.binding())
    }

    @Test
    fun installationIdCommitFailureIsReported() = runTest {
        assertFailsWith<IllegalStateException> { AndroidPushDevice(FailingCommit(prefs)).installationId() }
    }

    @Test
    fun bindingCommitFailureIsReported() = runTest {
        val device = AndroidPushDevice(FailingCommit(prefs))
        assertFailsWith<IllegalStateException> { device.saveBinding(PushBinding(7, "b7")) }
        assertFailsWith<IllegalStateException> { device.saveBinding(null) }
    }
}

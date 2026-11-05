package com.ssafy.e106.core.push

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

/**
 * FCM 토큰 · 설치 ID · 마지막 등록. 설치 ID 는 계정과 무관한 SharedPreferences 칸.
 * allowBackup=false 는 클라우드 백업만 막는다. 기기 간 이전(D2D)으로 이 파일이 새 기기에 옮겨지는 것은 dataExtractionRules 로 막아야 한다(나중에_할_일.md, 재준 님)
 * 디스크 쓰기(`commit()`)가 false 면 예외로 알린다 — 등록기가 저장 실패를 성공으로 보지 않고 다음 기회에 다시 한다.
 * 실패해도 값은 메모리에 남고, 다음에 성공하는 commit 이 파일 전체를 쓰므로 그때 함께 저장된다(binding 은 그 설치 ID 로 등록한 뒤에만 저장)
 */
internal class AndroidPushDevice(private val prefs: SharedPreferences) : PushDevice {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("tingle.push", Context.MODE_PRIVATE))

    override val platform = "ANDROID"

    private val _tokenRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val tokenRefreshed: SharedFlow<Unit> = _tokenRefreshed

    /** TingleMessagingService.onNewToken 이 부른다. 토큰 값은 넘기지 않는다 — 등록기가 [token] 으로 최신 값을 읽는다 */
    fun onNewToken() { _tokenRefreshed.tryEmit(Unit) }

    override suspend fun installationId(): String = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            prefs.getString(KEY_INSTALLATION, null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString(KEY_INSTALLATION, it).commit()) { "설치 ID 저장 실패" } }
        }
    }

    override suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
    }

    override suspend fun binding(): PushBinding? = withContext(Dispatchers.IO) {
        val id = prefs.getString(KEY_BINDING, null) ?: return@withContext null
        PushBinding(prefs.getLong(KEY_BINDING_USER, 0), id)
    }

    override suspend fun saveBinding(binding: PushBinding?) = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        if (binding == null) edit.remove(KEY_BINDING).remove(KEY_BINDING_USER) else edit.putString(KEY_BINDING, binding.id).putLong(KEY_BINDING_USER, binding.userId)
        check(edit.commit()) { "푸시 등록 정보 저장 실패" }
    }

    private companion object {
        const val KEY_INSTALLATION = "installationId"
        const val KEY_BINDING = "bindingId"
        const val KEY_BINDING_USER = "bindingUserId"
    }
}

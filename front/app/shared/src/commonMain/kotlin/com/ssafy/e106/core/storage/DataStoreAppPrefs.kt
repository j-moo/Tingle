package com.ssafy.e106.core.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.network.catchingNonCancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * [AppPrefs] 구현. 값은 JSON 문자열로 `"<userId>:<name>"` 키에 둔다. 캐시는 [cache], [PrefKey.durable] 키는 [durable] 파일에 둔다(깨지면 캐시 파일만 비운다).
 * 숫자로 시작하지 않는 키(설치 ID 등)는 계정과 무관해 지우지 않는다.
 * 계정 데이터 삭제는 세션 상태를 보고 여기서 한다: SignedOut 이면 모든 계정 키, SignedIn 이면 다른 계정 키.
 * 로그아웃 코드가 부르지 않아도 되고, 지우기 전에 앱이 죽어도 다음 실행에서 다시 지운다. 지우는 사이에도 [flow] 는 이미 null 이고 [set] 은 막힌다
 */
internal class DataStoreAppPrefs(
    private val cache: DataStore<Preferences>,
    private val durable: DataStore<Preferences>,
    private val session: StateFlow<Session>,
    scope: CoroutineScope,
) : AppPrefs {
    init {
        scope.launch {
            session.map { it.userKey() }.distinctUntilChanged().collect { key ->
                when (key) {
                    UserKey.Unknown -> Unit                      // Restoring: 아직 누구인지 모른다
                    UserKey.None -> clearUsers(except = null)
                    is UserKey.Of -> clearUsers(except = key.userId)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun <T> flow(key: PrefKey<T>): Flow<T?> = session.map { (it as? Session.SignedIn)?.userId }.distinctUntilChanged().flatMapLatest { userId ->
        if (userId == null) flowOf(null)
        // 파일을 못 읽으면 캐시가 없는 것으로 보이고, 잠시 뒤 다시 구독한다. 화면에 예외를 올리지 않는다
        else key.store.data.map<Preferences, T?> { prefs -> prefs[userKey(userId, key)]?.let { decode(key, it) } }
            .retryWhen { _, _ -> emit(null); delay(READ_RETRY_MILLIS); true }.distinctUntilChanged()
    }

    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        catchingNonCancel {                              // 캐시라 못 써도 화면은 서버 응답으로 그린다
            key.store.edit { prefs ->
                // 쓰기 직전에 다시 본다. 요청을 보낸 뒤 로그아웃 · 계정 전환이 있었으면 그 응답은 버린다
                if (currentUser() != forUser) return@edit
                val k = userKey(forUser, key)
                if (value == null) prefs.remove(k) else prefs[k] = ApiJson.encodeToString(key.serializer, value)
            }
        }
    }

    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
        if (currentUser() != forUser) return PrefRead.OtherUser
        val raw = catchingNonCancel { key.store.data.first()[userKey(forUser, key)] }
            .getOrElse { return if (it is CorruptionException) PrefRead.Corrupt else PrefRead.Unreadable }   // 깨진 파일은 다시 읽어도 같다
        if (currentUser() != forUser) return PrefRead.OtherUser   // 읽는 사이 로그아웃 · 계정 전환. 지워지기 전 값을 넘기지 않는다
        if (raw == null) return PrefRead.Missing
        return catchingNonCancel { ApiJson.decodeFromString(key.serializer, raw) }.fold({ PrefRead.Found(it) }, { PrefRead.Corrupt })
    }

    private fun currentUser() = (session.value as? Session.SignedIn)?.userId

    private val PrefKey<*>.store get() = if (durable) this@DataStoreAppPrefs.durable else cache

    private suspend fun clearUsers(except: Long?) = listOf(cache, durable).forEach { clearUsers(it, except) }

    private suspend fun clearUsers(store: DataStore<Preferences>, except: Long?) {
        catchingNonCancel {                              // 실패해도 앱은 돈다. 다음 상태 변화나 다음 실행에 다시 지운다(읽기 · 쓰기는 계정을 다시 확인해 막는다)
            store.edit { prefs ->
                prefs.asMap().keys.filter { k -> k.name.substringBefore(':', "").toLongOrNull().let { it != null && it != except } }.forEach { prefs.remove(it) }
            }
        }
    }

    // 앱이 바뀌며 DTO 모양이 달라진 값은 없는 것으로 본다. 다음 서버 응답이 덮어쓴다
    private fun <T> decode(key: PrefKey<T>, json: String): T? = runCatching { ApiJson.decodeFromString(key.serializer, json) }.getOrNull()

    private sealed interface UserKey {
        data object Unknown : UserKey
        data object None : UserKey
        data class Of(val userId: Long) : UserKey
    }

    private fun Session.userKey(): UserKey = when (this) {
        Session.Restoring -> UserKey.Unknown
        Session.SignedOut -> UserKey.None
        is Session.SignedIn -> UserKey.Of(userId)
    }

    private companion object {
        const val READ_RETRY_MILLIS = 1_000L

        fun userKey(userId: Long, key: PrefKey<*>) = stringPreferencesKey("$userId:${key.name}")
    }
}

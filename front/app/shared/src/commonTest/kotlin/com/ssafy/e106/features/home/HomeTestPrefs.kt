package com.ssafy.e106.features.home

import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.PrefKey
import com.ssafy.e106.core.storage.PrefRead
import kotlinx.coroutines.flow.flowOf

/** 앱 재조회에 사용할 저장소와 쓰기 오류를 재현하는 테스트 대역. */
internal class HomeTestPrefs : AppPrefs {
    private val values = mutableMapOf<Pair<Long, String>, Any>()
    var refuseWrites = false

    override fun <T> flow(key: PrefKey<T>) = flowOf<T?>(null)

    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        if (refuseWrites) return
        val address = forUser to key.name
        if (value == null) values.remove(address) else values[address] = value
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> =
        (values[forUser to key.name] as T?)?.let { PrefRead.Found(it) } ?: PrefRead.Missing
}

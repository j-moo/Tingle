package com.ssafy.e106.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.LocalResourceReader
import org.jetbrains.compose.resources.ResourceReader

@OptIn(ExperimentalResourceApi::class)
@Composable
internal actual fun ProvidePreviewResources(content: @Composable () -> Unit) {
    val base = LocalResourceReader.current
    val preview = LocalInspectionMode.current
    // 화면 구조가 Preview 여부로 바뀌지 않게 항상 Provider 를 두고 값만 고른다
    val reader = remember(base, preview) { if (preview) PreviewResourceReader(base) else base }
    CompositionLocalProvider(LocalResourceReader provides reader) { content() }
}

/**
 * Preview 전용. 리소스 파일을 통째로 한 번 읽어 메모리에 두고 필요한 부분만 잘라 준다 — 부분 읽기(readPart)를 원래 리더에 맡기지 않는다.
 * 원래 리더의 부분 읽기는 파일을 건너뛰며(skip) 읽는데, Android Studio 의 VFS 스트림은 파일 끝에서 skip 이 -1 을 돌려줘
 * Compose 리소스의 건너뛰기 반복이 끝나지 않는다(Preview 무한 대기). 디스크 파일(file:)이면 java.io 로, 아니면 원래 리더의 통째 읽기로 받는다.
 */
@OptIn(ExperimentalResourceApi::class)
private class PreviewResourceReader(private val base: ResourceReader) : ResourceReader {
    // Preview 한 번(remember)마다 새로 읽는다 — 재빌드 뒤 예전 파일 내용을 계속 주지 않게
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private fun file(path: String): File? = PreviewResourceReader::class.java.classLoader?.getResource(path)
        ?.takeIf { it.protocol == "file" }?.let { File(it.toURI()) }?.takeIf { it.isFile }

    private suspend fun bytes(path: String): ByteArray = cache[path] ?: (file(path)?.readBytes() ?: base.read(path)).also { cache[path] = it }

    override suspend fun read(path: String): ByteArray = bytes(path)
    override suspend fun readPart(path: String, offset: Long, size: Long): ByteArray {
        val all = bytes(path)
        val end = offset + size
        // 빌드가 새 파일을 만들었는데 IDE 가 예전(짧은) 내용을 주면 무한 대기 대신 바로 알린다
        check(end <= all.size) { "Preview 리소스가 최신이 아니에요($path). Build & Refresh 를 눌러 주세요." }
        return all.copyOfRange(offset.toInt(), end.toInt())
    }
    override fun getUri(path: String): String = base.getUri(path)

}

package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.keyring.data.KeyringResetStore
import com.ssafy.e106.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** 소유 목록과 별개로 보관한 실행을 보여준다. 새 실행 생성이나 서버 변경은 하지 않는다. */
@Composable internal fun KeyringResetRecoveryLinks(owner: Long, onOpen: (Long) -> Unit,
    store: KeyringResetStore = koinInject()) {
    val revision by store.revision.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    var ids by remember(owner) { mutableStateOf<List<Long>>(emptyList()) }
    var failed by remember(owner) { mutableStateOf(false) }
    LifecycleResumeEffect(owner) { resumed = true; onPauseOrDispose { resumed = false } }
    LaunchedEffect(owner, revision, resumed) {
        ids = emptyList(); failed = false
        if (resumed) try { ids = store.read(owner).map { it.deviceId } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    if (failed) Text(stringResource(Res.string.reset_recovery_unreadable))
    ids.forEach { id -> AppButton(stringResource(Res.string.reset_recovery_entry, id.toString()),
        variant = AppButtonVariant.Secondary, onClick = { onOpen(id) }) }
}

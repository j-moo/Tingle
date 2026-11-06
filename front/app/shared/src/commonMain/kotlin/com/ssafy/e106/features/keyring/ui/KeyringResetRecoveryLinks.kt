package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import com.ssafy.e106.core.ui.theme.AppColors
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.keyring.data.KeyringResetStore
import com.ssafy.e106.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * 끝나지 않은 초기화 중 내 키링 목록에 더 이상 없는 키링(서버 연결은 이미 해제됨)을 목록과 같은 카드로 보여준다.
 * 목록에 남은 키링은 그 카드의 "초기화"로 이어서 하므로 [skip] 으로 뺀다. 새 실행 생성이나 서버 변경은 하지 않는다.
 */
@Composable internal fun KeyringResetRecoveryLinks(owner: Long, onOpen: (Long) -> Unit, skip: Set<Long> = emptySet(),
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
    val left = ids.filterNot { it in skip }
    left.forEachIndexed { index, id ->
        // 여러 개면 "초기화 중인 키링 1, 2…"로 구분한다(서버 번호는 보여주지 않는다)
        val title = stringResource(Res.string.reset_recovery_entry, if (left.size > 1) " ${index + 1}" else "")
        val label = stringResource(Res.string.owned_keyrings_reset_for, title)
        KeyringCard(title, selected = false, onClick = null,
            trailing = { AppButton(stringResource(Res.string.owned_keyrings_reset), Modifier.semantics { contentDescription = label },
                variant = AppButtonVariant.Secondary, onClick = { onOpen(id) }) }) {
            Text(stringResource(Res.string.reset_recovery_entry_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

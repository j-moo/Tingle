package com.ssafy.e106

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.theme.AppLayer
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.core.ui.theme.appLayer
import com.ssafy.e106.core.ui.theme.behindLayer
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.core.ui.theme.screenPadding

@Composable
fun App() {
    AppTheme {
        var layer by rememberSaveable { mutableStateOf(0) }
        val current = AppLayer.entries[layer]
        Box(Modifier.fillMaxSize()) {
            // 뒤 배경: 레이어가 높아지면 흐려지고 어두워진다
            Surface(modifier = Modifier.fillMaxSize().behindLayer(current)) {
                Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().screenPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large, Alignment.CenterVertically),
                ) {
                    Text("E106", style = MaterialTheme.typography.titleLarge)
                    Text("Android와 iOS가 공유하는 Kotlin 화면입니다.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { layer = (layer + 1) % AppLayer.entries.size }) {
                        Text("레이어 올리기: ${current.name}")
                    }
                }
            }
            // 떠 있는 레이어: 단계에 맞는 그림자
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Section)
                    .fillMaxWidth().appLayer(current),
                shape = AppShapes.Control,
            ) {
                Text(
                    "${current.name} · 그림자 ${current.elevation}",
                    modifier = Modifier.padding(AppSpacing.Large),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

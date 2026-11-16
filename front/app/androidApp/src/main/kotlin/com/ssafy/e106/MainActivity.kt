package com.ssafy.e106

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ssafy.e106.features.auth.ui.AuthenticationHost
import com.ssafy.e106.features.home.ui.SignedInServiceHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 밝은 배경이라 휴대폰이 어두운 모드여도 상태 바 · 내비게이션 바 아이콘을 어둡게
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        setContent { App { AuthenticationHost(signedIn = { SignedInServiceHost(it) }) } }
    }
}

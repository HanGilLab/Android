package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.feature.home.HomeRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val showDiagnostics = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent { HanGilTheme { HomeRoute(showDiagnostics = showDiagnostics) } }
    }
}

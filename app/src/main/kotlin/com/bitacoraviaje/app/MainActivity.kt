package com.bitacoraviaje.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.Box
import com.bitacoraviaje.app.ui.navigation.BitacoraNavHost
import com.bitacoraviaje.app.ui.theme.BitacoraTheme
import dagger.hilt.android.AndroidEntryPoint
import com.bitacoraviaje.app.map.MapRenderer
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var mapRenderer: MapRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BitacoraTheme {
                // Exposes Modifier.testTag as resource-id to uiautomator (E2E via adb).
                @OptIn(ExperimentalComposeUiApi::class)
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    BitacoraNavHost(mapRenderer)
                }
            }
        }
    }
}

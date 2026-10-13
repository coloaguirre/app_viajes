package com.bitacoraviaje.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    /** Pending "new session" request from a shortcut, tile or widget; consumed once by the nav host (PLAN 5.3). */
    private val newSessionRequest = kotlinx.coroutines.flow.MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleAction(intent)
        setContent {
            BitacoraTheme {
                // Exposes Modifier.testTag as resource-id to uiautomator (E2E via adb).
                @OptIn(ExperimentalComposeUiApi::class)
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    val request by newSessionRequest.collectAsState()
                    BitacoraNavHost(mapRenderer, newSessionRequest = request)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    /** Shortcuts and tiles (DISENO §14.3): the voice bubble opens on top; "new session" is routed to the nav host. */
    private fun handleAction(intent: Intent?) {
        when (com.bitacoraviaje.app.quick.ShortcutActions.of(intent)) {
            com.bitacoraviaje.app.quick.ShortcutActions.Kind.VOICE_NOTE -> startActivity(com.bitacoraviaje.app.voice.VoiceNoteActivity.intent(this))
            com.bitacoraviaje.app.quick.ShortcutActions.Kind.NEW_SESSION -> newSessionRequest.value++
            null -> Unit
        }
        intent?.action = null
    }
}

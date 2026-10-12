package com.bitacoraviaje.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bitacoraviaje.app.ui.navigation.BitacoraNavHost
import com.bitacoraviaje.app.ui.theme.BitacoraTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BitacoraTheme {
                BitacoraNavHost()
            }
        }
    }
}

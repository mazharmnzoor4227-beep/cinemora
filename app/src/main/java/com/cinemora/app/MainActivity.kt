package com.cinemora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cinemora.app.ui.CinemoraApp
import com.cinemora.app.ui.theme.CinemoraTheme
import com.cinemora.app.ui.theme.Night

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CinemoraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Night),
                    color = Night
                ) {
                    CinemoraApp()
                }
            }
        }
    }
}

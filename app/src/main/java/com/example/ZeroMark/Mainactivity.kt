package com.example.zeromark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.*
import com.example.zeromark.model.CanvasSettings
import com.example.zeromark.ui.CanvasSelectionScreen
import com.example.zeromark.ui.DrawingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        // Hide system bars for a true fullscreen experience
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            var canvasSettings by remember { mutableStateOf<CanvasSettings?>(null) }
            
            if (canvasSettings == null) {
                CanvasSelectionScreen { settings ->
                    canvasSettings = settings
                }
            } else {
                DrawingScreen(canvasSettings!!)
            }
        }
    }
}

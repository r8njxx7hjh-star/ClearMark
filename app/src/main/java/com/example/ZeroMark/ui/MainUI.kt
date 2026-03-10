package com.example.ZeroMark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*

@Preview(showBackground = true)
@Composable
fun ColorGridPopupPreview() {
    val sampleColors = listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color.Green, Color.Yellow)
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        ColorGridPopup(
            colors = sampleColors,
            selectedColorIndex = 1,
            onColorSelected = {},
            onUpdateColor = { _, _ -> },
            onAddColor = {},
            onDeleteColor = {},
            onReorderColors = {},
            onDismiss = {}
        )
    }
}

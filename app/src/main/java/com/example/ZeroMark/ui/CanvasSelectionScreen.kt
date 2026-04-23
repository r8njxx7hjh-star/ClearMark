package com.example.zeromark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zeromark.model.CanvasSettings
import com.example.zeromark.model.CanvasType
import com.example.zeromark.model.GridType
import com.example.zeromark.persistence.CanvasPersistenceManager
import com.example.zeromark.canvas.model.Stroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasSelectionScreen(
    onCanvasSelected: (CanvasSettings) -> Unit,
    onCanvasLoaded: (CanvasSettings, List<Stroke>) -> Unit
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf(CanvasType.INFINITE) }
    var selectedGrid by remember { mutableStateOf(GridType.BLANK) }
    var widthText by remember { mutableStateOf("1200") }
    var heightText by remember { mutableStateOf("1800") }

    val loadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                try {
                    val settingsOut = arrayOfNulls<CanvasSettings>(1)
                    val strokes = CanvasPersistenceManager.loadCanvas(context, it, com.example.zeromark.canvas.model.CanvasModel(CanvasSettings()), settingsOut)
                    if (settingsOut[0] != null) {
                        onCanvasLoaded(settingsOut[0]!!, strokes)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("Select Canvas") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { loadLauncher.launch(arrayOf("application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Existing (.cmark)")
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("Or Start New Canvas", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text("Canvas Type", style = MaterialTheme.typography.titleMedium)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(200.dp)
            ) {
                items(CanvasType.values()) { type ->
                    CanvasOptionCard(
                        label = type.name.replace("_", " "),
                        selected = selectedType == type,
                        onClick = { selectedType = type }
                    )
                }
            }

            if (selectedType == CanvasType.FIXED) {
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    TextField(
                        value = widthText,
                        onValueChange = { widthText = it },
                        label = { Text("Width") },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    TextField(
                        value = heightText,
                        onValueChange = { heightText = it },
                        label = { Text("Height") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Grid Type", style = MaterialTheme.typography.titleMedium)
            Row {
                GridType.values().forEach { grid ->
                    CanvasOptionCard(
                        label = grid.name,
                        selected = selectedGrid == grid,
                        onClick = { selectedGrid = grid },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val w = if (selectedType == CanvasType.FIXED) widthText.toIntOrNull() ?: 1200 else null
                    val h = if (selectedType == CanvasType.FIXED) heightText.toIntOrNull() ?: 1800 else null
                    onCanvasSelected(CanvasSettings(selectedType, selectedGrid, w, h))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start New Drawing")
            }
        }
    }
}

@Composable
fun CanvasOptionCard(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .padding(4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label)
        }
    }
}

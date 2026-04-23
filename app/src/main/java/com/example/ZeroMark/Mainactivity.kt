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
import androidx.compose.ui.platform.LocalContext
import com.example.zeromark.model.CanvasSettings
import com.example.zeromark.ui.CanvasSelectionScreen
import com.example.zeromark.ui.DrawingScreen
import com.example.zeromark.ui.HomeScreen
import com.example.zeromark.canvas.model.Stroke
import com.example.zeromark.data.Note
import com.example.zeromark.data.NoteRepository
import com.example.zeromark.data.Notebook
import android.net.Uri
import com.example.zeromark.persistence.CanvasPersistenceManager

enum class Screen { HOME, SELECTION, DRAWING }

data class AppState(
    val screen: Screen = Screen.HOME,
    val selectedNotebook: Notebook? = null,
    val selectedNote: Note? = null,
    val canvasSettings: CanvasSettings = CanvasSettings(),
    val initialStrokes: List<Stroke> = emptyList()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            var appState by remember { mutableStateOf(AppState()) }
            val context = LocalContext.current
            val repository = remember { NoteRepository(context) }

            when (appState.screen) {
                Screen.HOME -> {
                    HomeScreen(
                        onNoteSelected = { notebook, note ->
                            try {
                                val file = repository.getNoteFile(note)
                                val uri = Uri.fromFile(file)
                                val settingsOut = arrayOfNulls<CanvasSettings>(1)
                                // We use a dummy model just to load the strokes
                                val strokes = CanvasPersistenceManager.loadCanvas(
                                    context, uri, 
                                    com.example.zeromark.canvas.model.CanvasModel(CanvasSettings()), 
                                    settingsOut
                                )
                                appState = appState.copy(
                                    screen = Screen.DRAWING,
                                    selectedNotebook = notebook,
                                    selectedNote = note,
                                    canvasSettings = settingsOut[0] ?: CanvasSettings(),
                                    initialStrokes = strokes
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        onCreateNote = { notebook ->
                            appState = appState.copy(
                                screen = Screen.SELECTION,
                                selectedNotebook = notebook
                            )
                        }
                    )
                }
                Screen.SELECTION -> {
                    CanvasSelectionScreen(
                        onCanvasSelected = { settings ->
                            // Create the note record first
                            val note = repository.createNote(appState.selectedNotebook!!.id, "New Note")
                            appState = appState.copy(
                                screen = Screen.DRAWING,
                                selectedNote = note,
                                canvasSettings = settings,
                                initialStrokes = emptyList()
                            )
                        },
                        onCanvasLoaded = { settings, strokes ->
                            // For simplicity, we just jump to drawing. 
                            // In a real app we might want to "import" it into the notebook.
                            appState = appState.copy(
                                screen = Screen.DRAWING,
                                canvasSettings = settings,
                                initialStrokes = strokes
                            )
                        }
                    )
                }
                Screen.DRAWING -> {
                    DrawingScreen(
                        canvasSettings = appState.canvasSettings,
                        initialStrokes = appState.initialStrokes,
                        notebook = appState.selectedNotebook,
                        note = appState.selectedNote,
                        onBack = { appState = appState.copy(screen = Screen.HOME) },
                        onHomeClick = { appState = appState.copy(screen = Screen.HOME) }
                    )
                }
            }
        }
    }
}

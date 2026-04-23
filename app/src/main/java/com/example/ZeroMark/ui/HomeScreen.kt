package com.example.zeromark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zeromark.data.Note
import com.example.zeromark.data.NoteRepository
import com.example.zeromark.data.Notebook
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNoteSelected: (Notebook, Note) -> Unit,
    onCreateNote: (Notebook) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { NoteRepository(context) }
    
    var notebooks by remember { mutableStateOf(repository.getNotebooks()) }
    var selectedNotebook by remember { mutableStateOf<Notebook?>(null) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    
    var showCreateNotebookDialog by remember { mutableStateOf(false) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedNotebook) {
        selectedNotebook?.let {
            notes = repository.getNotes(it.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedNotebook == null) "Notebooks" else "Notes in ${selectedNotebook!!.name}") },
                navigationIcon = {
                    if (selectedNotebook != null) {
                        IconButton(onClick = { selectedNotebook = null }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedNotebook == null) showCreateNotebookDialog = true
                else showCreateNoteDialog = true
            }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedNotebook == null) {
                // List Notebooks
                if (notebooks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No notebooks yet. Create one!")
                    }
                } else {
                    LazyColumn {
                        items(notebooks) { notebook ->
                            ListItem(
                                headlineContent = { Text(notebook.name) },
                                leadingContent = { Icon(imageVector = Icons.Default.List, contentDescription = null) },
                                modifier = Modifier.clickable { selectedNotebook = notebook }
                            )
                        }
                    }
                }
            } else {
                // List Notes
                if (notes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No notes in this notebook.")
                    }
                } else {
                    LazyColumn {
                        items(notes) { note ->
                            ListItem(
                                headlineContent = { Text(note.name) },
                                leadingContent = { Icon(imageVector = Icons.Default.Edit, contentDescription = null) },
                                modifier = Modifier.clickable { onNoteSelected(selectedNotebook!!, note) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateNotebookDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateNotebookDialog = false },
            title = { Text("Create Notebook") },
            text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        repository.createNotebook(name)
                        notebooks = repository.getNotebooks()
                        showCreateNotebookDialog = false
                    }
                }) { Text("Create") }
            }
        )
    }

    if (showCreateNoteDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateNoteDialog = false },
            title = { Text("Create Note") },
            text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && selectedNotebook != null) {
                        onCreateNote(selectedNotebook!!) // This will lead to CanvasSelectionScreen
                        showCreateNoteDialog = false
                    }
                }) { Text("Next") }
            }
        )
    }
}

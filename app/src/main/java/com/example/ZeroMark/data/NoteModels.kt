package com.example.zeromark.data

import java.util.UUID

/**
 * Represents a Notebook which contains multiple Notes.
 * Maps to a physical directory on disk.
 */
data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Represents a single Note (page) within a Notebook.
 * Maps to a .cmark file on disk.
 */
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val notebookId: String,
    val name: String,
    val fileName: String, // e.g. "note_uuid.cmark"
    val lastModified: Long = System.currentTimeMillis()
)

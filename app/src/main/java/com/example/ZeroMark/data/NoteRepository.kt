package com.example.zeromark.data

import android.content.Context
import com.example.zeromark.canvas.model.Stroke
import com.example.zeromark.model.CanvasSettings
import com.example.zeromark.persistence.CanvasPersistenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Simple file-based manager for Notebooks and Notes.
 * Notebooks are folders in app's internal storage.
 * Metadata is stored in a master json file.
 */
class NoteRepository(private val context: Context) {

    private val rootDir = File(context.filesDir, "notebooks")
    private val metaFile = File(context.filesDir, "metadata.json")

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    fun getNotebooks(): List<Notebook> {
        val meta = readMeta()
        val notebooks = mutableListOf<Notebook>()
        val jArray = meta.optJSONArray("notebooks") ?: return emptyList()
        for (i in 0 until jArray.length()) {
            val obj = jArray.getJSONObject(i)
            notebooks.add(Notebook(obj.getString("id"), obj.getString("name"), obj.getLong("createdAt")))
        }
        return notebooks
    }

    fun createNotebook(name: String): Notebook {
        val notebook = Notebook(name = name)
        val meta = readMeta()
        val jArray = meta.optJSONArray("notebooks") ?: JSONArray()
        val obj = JSONObject()
        obj.put("id", notebook.id)
        obj.put("name", notebook.name)
        obj.put("createdAt", notebook.createdAt)
        jArray.put(obj)
        meta.put("notebooks", jArray)
        writeMeta(meta)
        
        File(rootDir, notebook.id).mkdirs()
        return notebook
    }

    fun getNotes(notebookId: String): List<Note> {
        val meta = readMeta()
        val notes = mutableListOf<Note>()
        val jArray = meta.optJSONArray("notes") ?: return emptyList()
        for (i in 0 until jArray.length()) {
            val obj = jArray.getJSONObject(i)
            if (obj.getString("notebookId") == notebookId) {
                notes.add(Note(
                    obj.getString("id"),
                    obj.getString("notebookId"),
                    obj.getString("name"),
                    obj.getString("fileName"),
                    obj.getLong("lastModified")
                ))
            }
        }
        return notes
    }

    fun createNote(notebookId: String, name: String): Note {
        val fileName = "note_${UUID.randomUUID()}.cmark"
        val note = Note(notebookId = notebookId, name = name, fileName = fileName)
        
        val meta = readMeta()
        val jArray = meta.optJSONArray("notes") ?: JSONArray()
        val obj = JSONObject()
        obj.put("id", note.id)
        obj.put("notebookId", note.notebookId)
        obj.put("name", note.name)
        obj.put("fileName", note.fileName)
        obj.put("lastModified", note.lastModified)
        jArray.put(obj)
        meta.put("notes", jArray)
        writeMeta(meta)
        
        return note
    }

    fun getNoteFile(note: Note): File {
        return File(File(rootDir, note.notebookId), note.fileName)
    }

    private fun readMeta(): JSONObject {
        if (!metaFile.exists()) return JSONObject()
        return try {
            JSONObject(metaFile.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeMeta(obj: JSONObject) {
        metaFile.writeText(obj.toString())
    }
}

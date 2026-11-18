package com.mdreader.data

import java.awt.EventQueue
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import java.util.UUID

class ImportException(message: String) : Exception(message)

/**
 * Turns a folder picked through a native file dialog into a project.
 * Re-importing the same folder refreshes its chapter list while keeping saved
 * reading progress (the project id is preserved).
 */
fun importFolder(folder: Path, store: LibraryStore): Result<Project> = runCatching {
    if (!folder.toFile().isDirectory) {
        throw ImportException("That isn't a folder.")
    }
    if (!ChapterSource.hasPermission(folder)) {
        throw ImportException("That folder isn't readable.")
    }

    val chapters = ChapterSource.listChapters(folder)
    if (chapters.isEmpty()) {
        throw ImportException("No .md or .txt files found in that folder.")
    }

    val name = ChapterSource.displayName(folder)
    val existing = store.load().firstOrNull { it.treeUri == folder.toString() }

    val project = Project(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name,
        treeUri = folder.toString(),
        chapters = chapters,
        importedAt = System.currentTimeMillis(),
    )
    store.addOrReplace(project)
    project
}

/**
 * Opens a native directory-chooser dialog and returns the selected folder,
 * or null if the user cancelled. Must be called on the EDT (Compose Desktop
 * event handlers run on the EDT), so this blocks briefly while the dialog is open.
 */
fun promptFolder(): Path? {
    var result: Path? = null
    val chooser = JFileChooser()
    chooser.dialogType = JFileChooser.CUSTOM_DIALOG
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "Choose the folder that holds your chapters"
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isMultiSelectionEnabled = false
    // Show all files but only directories can be selected
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    val status = chooser.showOpenDialog(null)
    if (status == JFileChooser.APPROVE_OPTION) {
        val selected = chooser.selectedFile
        if (selected != null) result = selected.toPath()
    }
    return result
}

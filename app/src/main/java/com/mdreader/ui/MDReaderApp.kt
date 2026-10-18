package com.mdreader.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mdreader.data.ChapterSource
import com.mdreader.data.ImportException
import com.mdreader.data.LibraryStore
import com.mdreader.data.Project
import com.mdreader.data.importFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MDReaderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LibraryStore(context.applicationContext) }
    val snackbarHostState = remember { SnackbarHostState() }

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) { store.load() }
        loading = false
    }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun reloadLibrary() {
        scope.launch {
            projects = withContext(Dispatchers.IO) { store.load() }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { importFolder(context, uri, store) }
            projects = withContext(Dispatchers.IO) { store.load() }
            importing = false
            result.fold(
                onSuccess = { notify("Imported \"${it.name}\" \u00b7 ${it.chapters.size} chapters") },
                onFailure = { notify(it.message ?: "Couldn't import that folder.") },
            )
        }
    }

    val openProject = projects.firstOrNull { it.id == openProjectId }

    if (openProject == null) {
        LibraryScreen(
            projects = projects,
            progressOf = { store.progress(it) },
            loading = loading,
            importing = importing,
            snackbarHostState = snackbarHostState,
            onImport = { folderPicker.launch(null) },
            onOpen = { openProjectId = it.id },
            onRefresh = { project ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        refreshProject(context, project, store)
                    }
                    reloadLibrary()
                    result.fold(
                        onSuccess = { notify("Updated \u00b7 ${it.chapters.size} chapters") },
                        onFailure = { notify(it.message ?: "Couldn't refresh that folder.") },
                    )
                }
            },
            onRemove = { project ->
                scope.launch {
                    withContext(Dispatchers.IO) { store.remove(project.id) }
                    projects = withContext(Dispatchers.IO) { store.load() }
                    notify("Removed \"${project.name}\"")
                }
            },
        )
    } else {
        ReaderScreen(
            project = openProject,
            initialProgress = store.progress(openProject.id),
            onBack = { openProjectId = null },
            onProgress = { progress ->
                scope.launch(Dispatchers.IO) { store.setProgress(openProject.id, progress) }
            },
        )
    }
}

/**
 * Re-lists a folder to pick up chapters added or removed outside the app.
 * The saved reading position is preserved because the project id is kept.
 */
private fun refreshProject(
    context: Context,
    project: Project,
    store: LibraryStore,
): Result<Project> = runCatching {
    val treeUri = Uri.parse(project.treeUri)
    if (!ChapterSource.hasPermission(context, treeUri)) {
        throw ImportException("The folder is no longer accessible. Import it again.")
    }

    val chapters = ChapterSource.listChapters(context, treeUri)
    if (chapters.isEmpty()) {
        throw ImportException("No .md or .txt files are left in that folder.")
    }

    val updated = project.copy(chapters = chapters)
    store.addOrReplace(updated)
    updated
}

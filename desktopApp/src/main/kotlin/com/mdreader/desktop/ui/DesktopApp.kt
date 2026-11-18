package com.mdreader.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.mdreader.data.Project
import com.mdreader.data.importFolder
import com.mdreader.data.refreshProject
import com.mdreader.desktop.data.DesktopStorage
import com.mdreader.desktop.data.FsNovelFolderAccess
import com.mdreader.desktop.platform.NativeFolderPicker
import com.mdreader.desktop.theme.DesktopReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DesktopApp {
    var requestImport: (() -> Unit)? = null
    var requestToggleRail: (() -> Unit)? = null
}

@Composable
fun DesktopApp() {
    DesktopReaderTheme {
        val scope = rememberCoroutineScope()
        val store = remember { DesktopStorage.libraryStore() }
        val snackbar = remember { SnackbarHostState() }

        var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var importing by remember { mutableStateOf(false) }
        var textSizeLevel by remember { mutableIntStateOf(1) }
        var openProjectId by remember { mutableStateOf<String?>(null) }
        var railOpen by remember { mutableStateOf(true) }

        fun reload() {
            scope.launch {
                projects = withContext(Dispatchers.IO) { store.load() }
            }
        }

        fun notify(message: String) {
            scope.launch { snackbar.showSnackbar(message) }
        }

        fun runImport() {
            val path = NativeFolderPicker.pickDirectory() ?: return
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    importFolder(path, FsNovelFolderAccess, store)
                }
                projects = withContext(Dispatchers.IO) { store.load() }
                importing = false
                result.fold(
                    onSuccess = { notify("Imported \"${it.name}\" · ${it.chapters.size} chapters") },
                    onFailure = { notify(it.message ?: "Couldn't import that folder.") },
                )
            }
        }

        DisposableEffect(Unit) {
            DesktopApp.requestImport = { runImport() }
            DesktopApp.requestToggleRail = { railOpen = !railOpen }
            onDispose {
                DesktopApp.requestImport = null
                DesktopApp.requestToggleRail = null
            }
        }

        LaunchedEffect(Unit) {
            projects = withContext(Dispatchers.IO) { store.load() }
            textSizeLevel = store.textSizeLevel()
            loading = false
        }

        val openProject = projects.firstOrNull { it.id == openProjectId }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.isCtrlPressed && event.key == Key.O -> {
                            runImport()
                            true
                        }
                        openProject != null && event.key == Key.Escape -> {
                            openProjectId = null
                            true
                        }
                        else -> false
                    }
                },
        ) {
            AppChrome(onImport = { runImport() })
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                if (openProject == null) {
                    DesktopLibraryScreen(
                        projects = projects,
                        progressOf = { store.progress(it) },
                        loading = loading,
                        importing = importing,
                        snackbarHostState = snackbar,
                        onOpen = { openProjectId = it.id },
                        onRefresh = { project ->
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    refreshProject(project, FsNovelFolderAccess, store)
                                }
                                reload()
                                result.fold(
                                    onSuccess = { notify("Updated · ${it.chapters.size} chapters") },
                                    onFailure = { notify(it.message ?: "Couldn't refresh.") },
                                )
                            }
                        },
                        onRemove = { project ->
                            scope.launch {
                                withContext(Dispatchers.IO) { store.remove(project.id) }
                                reload()
                                notify("Removed \"${project.name}\"")
                            }
                        },
                    )
                } else {
                    DesktopReaderScreen(
                        project = openProject,
                        initialProgress = store.progress(openProject.id),
                        textSizeLevel = textSizeLevel,
                        railOpen = railOpen,
                        onToggleRail = { railOpen = !railOpen },
                        onTextSizeChange = { level ->
                            textSizeLevel = level
                            scope.launch(Dispatchers.IO) { store.setTextSizeLevel(level) }
                        },
                        onBack = { openProjectId = null },
                        onProgress = { progress ->
                            scope.launch(Dispatchers.IO) { store.setProgress(openProject.id, progress) }
                        },
                    )
                }
            }
        }
    }
}

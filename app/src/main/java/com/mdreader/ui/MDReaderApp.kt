package com.mdreader.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mdreader.data.ChapterSource
import com.mdreader.data.DownloadRequest
import com.mdreader.data.formatBytes
import com.mdreader.data.DownloadProgress
import com.mdreader.data.Vault
import com.mdreader.data.downloadChapters
import com.mdreader.data.planDownload
import com.mdreader.data.ImportException
import com.mdreader.data.LibraryStore
import com.mdreader.data.Project
import com.mdreader.data.Progress
import com.mdreader.data.remapTo
import com.mdreader.data.withChapters
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.NovelDetails
import com.mdreader.data.online.NovelHit
import com.mdreader.data.online.OnlineException
import com.mdreader.data.toChapters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.time.LocalDate
import kotlinx.coroutines.delay
import java.util.UUID
import com.mdreader.data.importFolder
import com.mdreader.data.checkOnlineProject
import com.mdreader.data.refreshOnlineProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MDReaderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LibraryStore(context.applicationContext) }
    val snackbarHostState = remember { SnackbarHostState() }
    val vault = remember { Vault.of(context.applicationContext) }
    // Bumped whenever a chapter is saved or deleted, so the page redraws its "saved" marks.
    var vaultTick by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf(DownloadProgress()) }
    var downloadFor by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var textSizeLevel by remember { mutableIntStateOf(1) }
    var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailPath by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf(NovelDetailState()) }
    var detailJob by remember { mutableStateOf<Job?>(null) }
    // The author list on screen, and the one a novel was opened from (Back from it returns there).
    var authorPage by rememberSaveable { mutableStateOf<AuthorTrail?>(null) }
    var cameFrom by rememberSaveable { mutableStateOf<AuthorTrail?>(null) }
    val authorLists = remember { mutableMapOf<String, AuthorList>() }
    // A novel being read straight from the site without being in the library.
    var preview by remember { mutableStateOf<Project?>(null) }
    var previewStart by remember { mutableStateOf<Progress?>(null) }

    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) { store.load() }
        textSizeLevel = store.textSizeLevel()
        loading = false
    }

    // Only problems are announced; successes (added, removed, updated, imported) stay silent.
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
                onSuccess = { },
                onFailure = { notify(it.message ?: "Couldn't import that folder.") },
            )
        }
    }

    var check by remember { mutableStateOf(LibraryCheck()) }

    /** Pull-to-check on the shelf: every online novel in turn, so the order and "+N" update live. */
    fun checkAll() {
        if (check.refreshing) return
        val targets = projects.filter { it.isOnline }
        if (targets.isEmpty()) return
        check = LibraryCheck(refreshing = true, total = targets.size)
        scope.launch {
            var added = 0
            var failed = 0
            targets.forEachIndexed { index, target ->
                checkOnlineProject(target, store).fold(
                    onSuccess = { added += (it.chapters.size - target.chapters.size).coerceAtLeast(0) },
                    onFailure = { failed++ },
                )
                projects = withContext(Dispatchers.IO) { store.load() }
                check = check.copy(done = index + 1)
                delay(300)
            }
            val result = when {
                failed == targets.size -> "Couldn't reach the site"
                added == 1 -> "1 new chapter"
                added > 1 -> "$added new chapters"
                failed > 0 -> "Up to date · $failed failed"
                else -> "Up to date"
            }
            check = LibraryCheck(result = result)
            delay(2500)
            if (check.result == result) check = LibraryCheck()
        }
    }

    /** Saves the chapters [request] asks for into the project's vault. Runs while the app is open. */
    fun startDownload(request: DownloadRequest) {
        val project = detail.project ?: return
        if (!detail.inLibrary) return
        if (download.running) {
            notify("A download is already running.")
            return
        }
        val plan = planDownload(project.chapters, vault.downloaded(project), detail.resumeIndex, request)
        downloadFor = project.id
        if (plan.isEmpty()) {
            download = DownloadProgress(message = "Everything asked for is already saved")
            return
        }
        val author = detail.details?.author
        download = DownloadProgress(running = true, total = plan.size)
        downloadJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    downloadChapters(project, plan, vault, author, LocalDate.now().toString()) { progress ->
                        download = progress
                        vaultTick++
                    }
                }
                download = result
            } catch (e: CancellationException) {
                download = DownloadProgress(message = "Stopped, ${vault.count(project)} chapters saved")
                throw e
            } finally {
                vaultTick++
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Deletes the saved chapters at [paths] (read ones, or all) and says how much room that gave back. */
    fun deleteDownloads(paths: List<String>) {
        val project = detail.project ?: return
        if (paths.isEmpty()) return
        scope.launch {
            if (downloadFor == project.id) downloadJob?.cancelAndJoin()
            val freed = withContext(Dispatchers.IO) {
                val bytes = vault.bytesOf(project, paths)
                vault.delete(project, paths)
                bytes
            }
            val count = if (paths.size == 1) "1 chapter" else "${paths.size} chapters"
            download = DownloadProgress(message = "$count deleted · ${formatBytes(freed)} freed")
            downloadFor = project.id
            vaultTick++
        }
    }

    /** Takes a novel out of the library, and its downloaded chapters off the device with it. */
    suspend fun removeNovel(project: Project) {
        if (downloadFor == project.id) downloadJob?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            store.remove(project.id)
            vault.deleteAll(project)
        }
        vaultTick++
    }

    val openProject = preview ?: projects.firstOrNull { it.id == openProjectId }

    fun libraryEntry(path: String) = projects.firstOrNull { it.onlinePath == path }

    fun loadDetail(path: String, title: String?) {
        detailJob?.cancel()
        detail = NovelDetailState(title = title)
        detailJob = scope.launch {
            try {
                val saved = libraryEntry(path)
                if (saved != null) {
                    // In the library: the list is already stored, so only the summary is fetched.
                    // Offline still works; the page just lacks the summary.
                    val info = try {
                        FreeWebNovelClient.loadNovelPage(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        NovelDetails(saved.name, "", "", emptyList(), "", 1)
                    }
                    val resume = store.progress(saved.id)?.remapTo(saved.chapters)?.chapterIndex
                    // Opening a novel clears its "+N"; novels saved before covers existed pick theirs up here.
                    val shown = saved.copy(
                        coverUrl = saved.coverUrl ?: info.cover.ifEmpty { null },
                        newChapters = 0,
                    )
                    if (shown != saved) {
                        withContext(Dispatchers.IO) { store.addOrReplace(shown) }
                        projects = withContext(Dispatchers.IO) { store.load() }
                    }
                    detail = NovelDetailState(details = info, project = shown, inLibrary = true, resumeIndex = resume)
                    return@launch
                }
                var loaded = 0
                val novel = FreeWebNovelClient.loadDetails(
                    path,
                    onDetails = { info -> detail = detail.copy(details = info) },
                    onPage = { page, number ->
                        loaded += page.chapters.size
                        detail = detail.copy(
                            pagesDone = number,
                            totalPages = page.totalPages,
                            loadedChapters = loaded,
                            totalChapters = page.totalChapters.coerceAtLeast(loaded),
                        )
                    },
                )
                val chapters = novel.chapters.toChapters()
                val project = Project(
                    id = UUID.randomUUID().toString(),
                    name = novel.details.title,
                    treeUri = "",
                    chapters = chapters,
                    importedAt = System.currentTimeMillis(),
                    onlinePath = path,
                    totalChapters = chapters.size,
                    coverUrl = novel.details.cover.ifEmpty { null },
                )
                detail = NovelDetailState(details = novel.details, project = project, inLibrary = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detail = NovelDetailState(title = title, error = (e as? OnlineException)?.message ?: "Couldn't load that novel.")
            }
        }
    }

    /** Pull-to-check: reloads the whole list and summary, keeping progress on the same chapter. */
    fun refreshDetail(path: String) {
        val current = detail.project ?: return
        if (detail.refreshing) return
        detail = detail.copy(refreshing = true, refreshResult = null)
        scope.launch {
            var result: String
            try {
                val novel = FreeWebNovelClient.loadDetails(path)
                val chapters = novel.chapters.toChapters()
                // Looking at the novel right now, so what this check finds is not "new" to the reader.
                val updated = current.withChapters(novel.details.title, chapters, novel.details.cover).copy(newChapters = 0)
                var resume = detail.resumeIndex
                if (detail.inLibrary) {
                    withContext(Dispatchers.IO) {
                        store.addOrReplace(updated)
                        store.progress(updated.id)?.let { saved ->
                            val remapped = saved.remapTo(chapters)
                            store.setProgress(updated.id, remapped)
                            resume = remapped.chapterIndex
                        }
                    }
                    projects = withContext(Dispatchers.IO) { store.load() }
                }
                val added = chapters.size - current.chapters.size
                result = when {
                    added > 0 -> if (added == 1) "1 new chapter" else "$added new chapters"
                    else -> "Up to date"
                }
                detail = detail.copy(details = novel.details, project = updated, resumeIndex = resume)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = (e as? OnlineException)?.message ?: "Couldn't check for updates."
            }
            detail = detail.copy(refreshing = false, refreshResult = result)
            delay(2500)
            if (detail.refreshResult == result) detail = detail.copy(refreshResult = null)
        }
    }

    if (openProject == null && authorPage != null) {
        val author = authorPage!!
        AuthorScreen(
            name = author.name,
            path = author.path,
            list = authorLists.getOrPut(author.path) { AuthorList() },
            onBack = {
                authorPage = null
                // A novel opened from this list replaced the page we came from; bring that back.
                val origin = author.origin
                if (origin != null && origin != detailPath) {
                    detailPath = origin
                    loadDetail(origin, null)
                }
            },
            onOpen = { hit: NovelHit ->
                cameFrom = author
                authorPage = null
                detailPath = hit.path
                loadDetail(hit.path, hit.title)
            },
        )
    } else if (openProject == null && detailPath != null) {
        NovelDetailScreen(
            state = detail,
            onBack = {
                detailJob?.cancel()
                val from = cameFrom
                cameFrom = null
                if (from != null) authorPage = from else detailPath = null
            },
            onAuthor = {
                val info = detail.details
                if (info != null && info.authorPath.isNotEmpty()) {
                    authorPage = AuthorTrail(info.author, info.authorPath, detailPath)
                }
            },
            onRetry = { detailPath?.let { loadDetail(it, detail.title) } },
            onRead = { index ->
                val project = detail.project ?: return@NovelDetailScreen
                previewStart = Progress(
                    chapterIndex = index,
                    chapterKey = project.chapters.getOrNull(index)?.uri,
                )
                preview = project
            },
            onRefresh = { detailPath?.let(::refreshDetail) },
            downloaded = detail.project?.let { project ->
                vaultTick // read so the marks follow every save
                vault.downloaded(project)
            }.orEmpty(),
            download = if (downloadFor == detail.project?.id) download else DownloadProgress(),
            onDownload = ::startDownload,
            onCancelDownload = ::cancelDownload,
            onDeleteDownloads = ::deleteDownloads,
            sizeOf = { paths -> detail.project?.let { vault.bytesOf(it, paths) } ?: 0L },
            onContinue = {
                val project = detail.project ?: return@NovelDetailScreen
                previewStart = store.progress(project.id)?.remapTo(project.chapters)
                preview = project
            },
            onToggleOrder = {
                val project = detail.project ?: return@NovelDetailScreen
                val updated = project.copy(newestFirst = !project.newestFirst)
                detail = detail.copy(project = updated)
                // Only novels in the library remember it; a preview just flips for now.
                if (detail.inLibrary) {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.addOrReplace(updated) }
                        projects = withContext(Dispatchers.IO) { store.load() }
                    }
                }
            },
            onToggleLibrary = {
                val project = detail.project ?: return@NovelDetailScreen
                scope.launch {
                    if (detail.inLibrary) {
                        removeNovel(project)
                    } else {
                        withContext(Dispatchers.IO) { store.addOrReplace(project) }
                    }
                    projects = withContext(Dispatchers.IO) { store.load() }
                    // Removing a novel also forgets its progress, so drop the Continue position too.
                    detail = detail.copy(
                        inLibrary = !detail.inLibrary,
                        resumeIndex = if (detail.inLibrary) null else detail.resumeIndex,
                    )
                }
            },
        )
    } else if (openProject == null) {
        LibraryScreen(
            projects = projects,
            progressOf = { store.progress(it) },
            loading = loading,
            importing = importing,
            snackbarHostState = snackbarHostState,
            check = check,
            onCheckAll = ::checkAll,
            onImport = { folderPicker.launch(null) },
            onOpenHit = { hit ->
                detailPath = hit.path
                loadDetail(hit.path, hit.title)
            },
            onOpen = { project ->
                val path = project.onlinePath
                if (path != null) {
                    detailPath = path
                    loadDetail(path, project.name)
                } else {
                    openProjectId = project.id
                }
            },
            onRefresh = { project ->
                scope.launch {
                    val result = if (project.isOnline) {
                        refreshOnlineProject(project, store)
                    } else {
                        withContext(Dispatchers.IO) { refreshProject(context, project, store) }
                    }
                    reloadLibrary()
                    result.fold(
                        onSuccess = { },
                        onFailure = { notify(it.message ?: "Couldn't refresh that novel.") },
                    )
                }
            },
            downloadedCount = { project -> vault.count(project) },
            onRemove = { project ->
                scope.launch {
                    removeNovel(project)
                    projects = withContext(Dispatchers.IO) { store.load() }
                }
            },
        )
    } else {
        ReaderScreen(
            project = openProject,
            initialProgress = previewStart?.takeIf { preview != null } ?: store.progress(openProject.id),
            textSizeLevel = textSizeLevel,
            onTextSizeChange = { level ->
                textSizeLevel = level
                scope.launch(Dispatchers.IO) { store.setTextSizeLevel(level) }
            },
            onBack = {
                preview = null
                previewStart = null
                openProjectId = null
            },
            onProgress = { progress ->
                if (detail.inLibrary && detail.project?.id == openProject.id) {
                    detail = detail.copy(resumeIndex = progress.chapterIndex)
                }
                // Only novels in the library remember where you were.
                scope.launch(Dispatchers.IO) {
                    if (store.find(openProject.id) != null) store.setProgress(openProject.id, progress)
                }
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

package com.mdreader.ui

import android.content.Context
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mdreader.data.Chapter
import com.mdreader.data.ChapterSource
import com.mdreader.data.ProjectKind
import com.mdreader.data.comics.ComicChallengeException
import com.mdreader.data.comics.ComicException
import com.mdreader.data.comics.ComicHit
import com.mdreader.data.comics.ComicSources
import com.mdreader.data.comics.ComicSource
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
import com.mdreader.data.ReadingHistory
import com.mdreader.data.remapTo
import com.mdreader.data.withChapters
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.NovelHit
import com.mdreader.data.online.OnlineException
import com.mdreader.data.online.asDetails
import com.mdreader.data.summaryIsStale
import com.mdreader.data.toChapters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import kotlinx.coroutines.delay
import java.util.UUID
import com.mdreader.data.importFolder
import com.mdreader.data.checkOnlineProject
import com.mdreader.data.refreshOnlineProject
import com.mdreader.data.comics.ComicCache
import com.mdreader.data.comics.ComicMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a comic's details and chapter list stay fresh in memory within one session. */
private const val COMIC_MEMO_TTL_MS = 10 * 60 * 1000L

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
    // A comic's detail page, and the comic currently open in the strip reader.
    var comicDetail by remember { mutableStateOf<ComicDetailState?>(null) }
    var comicReading by remember { mutableStateOf<Project?>(null) }
    var comicStart by remember { mutableStateOf<Progress?>(null) }

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
        val targets = projects.filter { it.isOnline && !it.isComic }
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
        val refresh = request == DownloadRequest.Refresh
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
                    downloadChapters(project, plan, vault, author, LocalDate.now().toString(), refresh = refresh) { progress ->
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
            CoverCache.evict(context.applicationContext, project.coverUrl)
        }
        vaultTick++
    }

    val openProject = preview ?: projects.firstOrNull { it.id == openProjectId }

    // The history list refetches whenever a reader opens or closes, or the shelf changes.
    var historyTick by remember { mutableStateOf(0) }
    val history by produceState(
        emptyList<ReadingHistory>(),
        projects,
        comicReading,
        preview,
        detailPath,
        historyTick,
    ) {
        value = withContext(Dispatchers.IO) { store.history() }
    }

    fun libraryEntry(path: String) = projects.firstOrNull { it.onlinePath == path }

    fun loadDetail(path: String, title: String?) {
        detailJob?.cancel()
        detail = NovelDetailState(title = title)
        detailJob = scope.launch {
            try {
                val saved = libraryEntry(path)
                if (saved != null) {
                    // Everything stored is on screen at once: chapters, cover and the last summary.
                    val resume = store.progress(saved.id)?.remapTo(saved.chapters)?.chapterIndex
                    val stored = saved.copy(newChapters = 0)
                    detail = NovelDetailState(
                        details = stored.asDetails(),
                        project = stored,
                        inLibrary = true,
                        resumeIndex = resume,
                        title = stored.name,
                    )
                    // Opening a novel clears its "+N" (and old entries pick up a cover later).
                    if (stored != saved) {
                        withContext(Dispatchers.IO) { store.addOrReplace(stored) }
                        projects = withContext(Dispatchers.IO) { store.load() }
                    }
                    // The page is already usable; the summary is refreshed behind it, and only
                    // when it is missing or old enough.
                    if (!saved.summaryIsStale(System.currentTimeMillis())) return@launch
                    val info = try {
                        FreeWebNovelClient.loadNovelPage(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch // offline: the stored page stays, no error
                    }
                    val enriched = stored.copy(
                        author = info.author,
                        authorPath = info.authorPath,
                        status = info.status,
                        synopsis = info.synopsis,
                        genres = info.genres,
                        rating = info.rating,
                        votes = info.votes,
                        coverUrl = stored.coverUrl ?: info.cover.ifEmpty { null },
                        summaryAt = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) { store.addOrReplace(enriched) }
                    projects = withContext(Dispatchers.IO) { store.load() }
                    detail = detail.copy(details = info, project = enriched, resumeIndex = resume)
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
                    id = stableNovelId(path),
                    name = novel.details.title,
                    treeUri = "",
                    chapters = chapters,
                    importedAt = System.currentTimeMillis(),
                    onlinePath = path,
                    totalChapters = chapters.size,
                    coverUrl = novel.details.cover.ifEmpty { null },
                    author = novel.details.author,
                    authorPath = novel.details.authorPath,
                    status = novel.details.status,
                    synopsis = novel.details.synopsis,
                    genres = novel.details.genres,
                    rating = novel.details.rating,
                    votes = novel.details.votes,
                    summaryAt = System.currentTimeMillis(),
                )
                detail = NovelDetailState(
                    details = novel.details,
                    project = project,
                    inLibrary = false,
                    // A search-read novel remembers too: the stable id carries the position.
                    resumeIndex = store.progress(project.id)?.remapTo(chapters)?.chapterIndex,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detail = NovelDetailState(title = title, error = (e as? OnlineException)?.message ?: "Couldn't load that novel.")
            }
        }
    }

    /**
     * Opens a comic fast: the search hit's cover shows immediately, details and the chapter list
     * fetch in parallel and render as each lands, everything within the session answers from
     * [ComicMemo], and the chapter you'd land on starts warming before you tap Read.
     */
    fun openComic(source: ComicSource, hit: ComicHit) {
        comicDetail = ComicDetailState(
            sourceId = source.id,
            path = hit.path,
            title = hit.title,
            coverUrl = hit.cover,
            loading = true,
        )
        scope.launch {
            try {
                coroutineScope {
                    val detailD = async {
                        ComicMemo.remember("details|${source.id}|${hit.path}", COMIC_MEMO_TTL_MS) {
                            source.details(hit.path)
                        }
                    }
                    val chapterD = async {
                        ComicMemo.remember("chapters|${source.id}|${hit.path}", COMIC_MEMO_TTL_MS) {
                            source.chapters(hit.path)
                        }
                    }
                    val saved = projects.firstOrNull {
                        it.isComic && it.sourceId == source.id && it.onlinePath == hit.path
                    }
                    // The poster renders the moment details arrive; the chapter list fills in behind.
                    val details = detailD.await()
                    comicDetail = ComicDetailState(
                        sourceId = source.id,
                        path = hit.path,
                        title = details.title,
                        details = details,
                        coverUrl = hit.cover,
                        chaptersLoading = true,
                        inLibrary = saved != null,
                    )
                    val chapters = chapterD.await()
                    val project = Project(
                        // A comic's identity is its source and path, so reading memory survives
                        // an open that never touched the Add to library button.
                        id = saved?.id ?: stableComicId(source.id, hit.path),
                        name = details.title,
                        treeUri = "",
                        chapters = chapters.map { Chapter(name = it.title, uri = it.path, remote = true) },
                        importedAt = saved?.importedAt ?: System.currentTimeMillis(),
                        onlinePath = hit.path,
                        totalChapters = chapters.size,
                        coverUrl = details.cover.ifEmpty { hit.cover }.ifEmpty { null },
                        author = details.author,
                        status = details.status,
                        synopsis = details.description,
                        genres = details.genres,
                        summaryAt = System.currentTimeMillis(),
                        kind = ProjectKind.COMIC,
                        sourceId = source.id,
                    )
                    val resume = store.progress(project.id)?.remapTo(project.chapters)?.chapterIndex
                    comicDetail = ComicDetailState(
                        sourceId = source.id,
                        path = hit.path,
                        title = details.title,
                        details = details,
                        chapters = chapters,
                        coverUrl = hit.cover,
                        loading = false,
                        inLibrary = saved != null,
                        project = project,
                        resumeIndex = resume,
                    )
                    // Warm the chapter Continue or Read lands on, so the reader opens loaded.
                    project.chapters.getOrNull(resume ?: 0)?.let { first ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { ComicCache.pages(context, source, first.uri) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? ComicException)?.message
                    ?: (e as? ComicChallengeException)?.message
                    ?: "Couldn't load that comic."
                val partial = comicDetail
                comicDetail = if (partial?.details != null) {
                    // The poster is up; only the chapter list failed, so keep what's showing.
                    partial.copy(chaptersLoading = false, chaptersError = message)
                } else {
                    ComicDetailState(
                        sourceId = source.id,
                        path = hit.path,
                        title = hit.title,
                        coverUrl = hit.cover,
                        loading = false,
                        error = message,
                    )
                }
            }
        }
    }

    /**
     * Reading is remembering: a comic joins the library the moment you open its reader, so the
     * shelf, the history and the saved position all have something to hold.
     */
    fun rememberComicReading(project: Project, inLibrary: Boolean) {
        if (inLibrary) return
        scope.launch {
            withContext(Dispatchers.IO) { store.addOrReplace(project) }
            projects = withContext(Dispatchers.IO) { store.load() }
        }
    }

    /** A history row reopens whatever it was: the comic's source, or the novel's page. */
    fun openHistory(entry: ReadingHistory) {
        val source = entry.sourceId?.let { ComicSources.byId(it) }
        if (source != null) {
            openComic(source, ComicHit(title = entry.title, path = entry.onlinePath))
        } else {
            cameFrom = null
            detailPath = entry.onlinePath
            loadDetail(entry.onlinePath, entry.title)
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
                val novel = FreeWebNovelClient.loadDetails(path, fresh = true)
                val chapters = novel.chapters.toChapters()
                // Looking at the novel right now, so what this check finds is not "new" to the reader.
                val updated = current.withChapters(novel.details.title, chapters, novel.details.cover).copy(
                    newChapters = 0,
                    author = novel.details.author,
                    authorPath = novel.details.authorPath,
                    status = novel.details.status,
                    synopsis = novel.details.synopsis,
                    genres = novel.details.genres,
                    rating = novel.details.rating,
                    votes = novel.details.votes,
                    summaryAt = System.currentTimeMillis(),
                )
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

    // The vault index is read on IO, not during composition, and follows every save.
    val downloaded by produceState(emptySet(), detail.project?.id, vaultTick) {
        value = withContext(Dispatchers.IO) {
            detail.project?.let { vault.downloaded(it) } ?: emptySet()
        }
    }

    if (comicReading != null) {
        val reading = comicReading!!
        val source = ComicSources.byId(reading.sourceId) ?: ComicSources.all.first()
        ComicReaderScreen(
            project = reading,
            source = source,
            initialProgress = comicStart,
            onBack = {
                comicStart = null
                comicReading = null
            },
            onProgress = { progress ->
                // Saved whether or not the shelf write has landed: a comic's reading position
                // is remembered from the first page turned, and the history row with it.
                scope.launch(Dispatchers.IO) {
                    store.setProgress(reading.id, progress)
                    store.recordHistory(historyOf(reading, progress))
                }
            },
        )
    } else if (comicDetail != null) {
        val page = comicDetail!!
        ComicDetailScreen(
            state = page,
            onBack = { comicDetail = null },
            onRetry = {
                val source = ComicSources.byId(page.sourceId)
                if (source != null) openComic(source, ComicHit(title = page.title, path = page.path))
            },
            onRead = { index ->
                page.project?.let { project ->
                    rememberComicReading(project, page.inLibrary)
                    comicStart = Progress(chapterIndex = index)
                    comicReading = project
                }
            },
            onContinue = {
                page.project?.let { project ->
                    rememberComicReading(project, page.inLibrary)
                    comicStart = store.progress(project.id)?.remapTo(project.chapters)
                    comicReading = project
                }
            },
            onToggleLibrary = {
                val project = page.project ?: return@ComicDetailScreen
                scope.launch {
                    if (page.inLibrary) {
                        removeNovel(project)
                    } else {
                        withContext(Dispatchers.IO) { store.addOrReplace(project) }
                    }
                    projects = withContext(Dispatchers.IO) { store.load() }
                    comicDetail = page.copy(
                        inLibrary = !page.inLibrary,
                        resumeIndex = if (page.inLibrary) null else page.resumeIndex,
                    )
                }
            },
            onOpenLibrary = {
                page.project?.let { project ->
                    comicStart = store.progress(project.id)?.remapTo(project.chapters)
                    comicReading = project
                }
            },
        )
    } else if (openProject == null && authorPage != null) {
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
            downloaded = downloaded,
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
            history = history,
            onOpenHistory = ::openHistory,
            onClearHistory = {
                scope.launch(Dispatchers.IO) { store.clearHistory() }
                historyTick++
            },
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
            onOpenComic = ::openComic,
            onOpen = { project ->
                when {
                    project.isComic -> {
                        comicStart = store.progress(project.id)
                        comicReading = project
                    }
                    project.onlinePath != null -> {
                        detailPath = project.onlinePath
                        loadDetail(project.onlinePath, project.name)
                    }
                    else -> openProjectId = project.id
                }
            },
            onRefresh = { project ->
                scope.launch {
                    val result = when {
                        project.isComic -> refreshComicChapters(project, store)
                        project.isOnline -> refreshOnlineProject(project, store)
                        else -> withContext(Dispatchers.IO) { refreshProject(context, project, store) }
                    }
                    reloadLibrary()
                    result.fold(
                        onSuccess = { },
                        onFailure = { notify(it.message ?: "Couldn't refresh that title.") },
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
                // Novels remember too: the stable id keeps a search-read's position, and the
                // history row survives even when the shelf never saw it.
                scope.launch(Dispatchers.IO) {
                    store.setProgress(openProject.id, progress)
                    store.recordHistory(historyOf(openProject, progress))
                }
            },
        )
    }
}

/**
 * Re-reads a comic's chapter list from its source and stores it, keeping the same project id so
 * the saved reading position still points at the same chapter path.
 */
/** A comic's shelf identity is its source and path, so reading memory finds it again. */
private fun stableComicId(sourceId: String, path: String): String =
    UUID.nameUUIDFromBytes("comic|$sourceId|$path".toByteArray()).toString()

/** A novel read from search carries its path as identity, so it resumes on the next open too. */
private fun stableNovelId(path: String): String =
    UUID.nameUUIDFromBytes("novel|$path".toByteArray()).toString()

/** One reading session as history: enough identity to reopen, enough position to resume. */
private fun historyOf(project: Project, progress: Progress) = ReadingHistory(
    id = project.id,
    kind = project.kind,
    sourceId = project.sourceId,
    onlinePath = project.onlinePath.orEmpty(),
    title = project.name,
    coverUrl = project.coverUrl,
    totalChapters = project.totalChapters ?: project.chapters.size,
    chapterIndex = progress.chapterIndex,
    chapterKey = progress.chapterKey,
    scrollIndex = progress.scrollIndex,
)

private suspend fun refreshComicChapters(
    project: Project,
    store: LibraryStore,
): Result<Project> = runCatching {
    val source = ComicSources.byId(project.sourceId)
        ?: throw ComicException("This comic has no source saved.")
    val path = project.onlinePath ?: throw ComicException("This comic has no series link saved.")
    val chapters = source.chapters(path)
    val updated = project.copy(
        chapters = chapters.map { Chapter(name = it.title, uri = it.path, remote = true) },
        totalChapters = chapters.size,
        updatedAt = System.currentTimeMillis(),
        newChapters = 0,
    )
    store.addOrReplace(updated)
    updated
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
    val treeUri = project.treeUri.toUri()
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

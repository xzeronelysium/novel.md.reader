package com.mdreader

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mdreader.data.Chapter
import com.mdreader.data.Fidelity
import com.mdreader.data.LibraryStore
import com.mdreader.data.Project
import com.mdreader.data.RewriteClient
import com.mdreader.data.RewritePipeline
import com.mdreader.data.RewriteStore
import com.mdreader.data.Vault
import com.mdreader.data.number
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import com.mdreader.md.MarkdownWriter
import com.mdreader.ui.theme.MDReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Prompt lab: rewrites a fixed set of test chapters with several models and saves everything under
 * `files/lab/<run>/`, for scoring on a computer. Nothing is written to the novels' own rewrites or
 * memory. Debug builds only.
 *
 * ```
 * adb shell am start -n com.mdreader/.LabActivity \
 *   --es run baseline --es models "gemma4:31b,gpt-oss:120b@high" --ei parallel 2
 * ```
 */
class LabActivity : ComponentActivity() {

    private val log = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val run = intent.getStringExtra("run")?.filter { it.isLetterOrDigit() || it in "-_." }?.ifEmpty { null }
            ?: "run-${System.currentTimeMillis() / 1000}"
        val models = (intent.getStringExtra("models") ?: DEFAULT_MODELS).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val chapters = (intent.getStringExtra("chapters") ?: DEFAULT_CHAPTERS).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val parallel = intent.getIntExtra("parallel", 2).coerceIn(1, 6)
        val strength = intent.getStringExtra("strength")

        // `--es preview lab/<run>/<file>.md` draws a file with the reader's own renderer instead.
        intent.getStringExtra("preview")?.let { path ->
            val blocks = runCatching { MarkdownParser.parse(File(filesDir, path).readText(), markdown = true) }.getOrDefault(emptyList())
            setContent {
                MDReaderTheme {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .statusBarsPadding()
                            .padding(horizontal = com.mdreader.ui.ScreenPad),
                    ) {
                        items(blocks) { com.mdreader.ui.BlockView(it) }
                    }
                }
            }
            return
        }

        setContent {
            MDReaderTheme {
                val state = rememberLazyListState()
                LaunchedEffect(Unit) { runLab(run, models, chapters, parallel, strength) }
                LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.lastIndex) }
                LazyColumn(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(16.dp),
                ) {
                    items(log) { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }

    private fun say(line: String) {
        log += line
        android.util.Log.i("MDReaderLab", line)
    }

    private suspend fun runLab(run: String, models: List<String>, refs: List<String>, parallel: Int, strength: String?) {
        val dir = File(filesDir, "lab/$run").apply { mkdirs() }
        val results = File(dir, "results.jsonl")
        val store = LibraryStore(this)
        val vault = Vault.of(this)
        val rewrites = RewriteStore.of(this)
        val base = rewrites.settings()
        if (base.apiKey.isBlank()) {
            say("No API key saved. Set it in the reader's rewrite settings first.")
            return
        }
        say("Run $run · prompt ${RewriteClient.PROMPT_VERSION} · ${models.size} models × ${refs.size} chapters")

        // Gather every chapter once, so each model gets exactly the same input.
        data class Case(val slug: String, val project: Project, val chapter: Chapter, val position: Int,
                        val source: String, val tail: String?, val memory: String)
        val cases = refs.mapNotNull { ref ->
            val found = store.load().firstNotNullOfOrNull { p ->
                p.chapters.indexOfFirst { it.uri == ref }.takeIf { it >= 0 }?.let { p to it }
            }
            if (found == null) {
                say("Not in the library: $ref")
                return@mapNotNull null
            }
            val (project, position) = found
            val chapter = project.chapters[position]
            runCatching {
                val source = text(project, chapter, vault)
                val tail = project.chapters.getOrNull(position - 1)?.let { RewriteClient.tailWords(text(project, it, vault)) }
                val memory = withContext(Dispatchers.IO) { rewrites.bible(project).context(source) }
                val slug = "%02d-%s".format(refs.indexOf(ref) + 1, project.name.filter { it.isLetterOrDigit() || it == ' ' }.trim().take(24).trim().replace(' ', '-'))
                File(dir, "$slug.source.md").writeText("# ${chapter.title}\n\n$source")
                Case(slug, project, chapter, position, source, tail, memory)
            }.onFailure { android.util.Log.e("MDReaderLab", "load $ref", it); say("Couldn't load $ref: ${it.message}") }.getOrNull()
        }
        say("${cases.size} chapters ready")

        val gate = Semaphore(parallel)
        coroutineScope {
            models.flatMap { spec -> cases.map { spec to it } }.map { (spec, case) ->
                async {
                    gate.withPermit {
                        val model = spec.substringBefore('@')
                        val effort = spec.substringAfter('@', "")
                        val settings = base.copy(model = model, effort = effort, strength = strength ?: base.strength)
                        val out = File(dir, Vault.safeTitle(spec).replace(' ', '_')).apply { mkdirs() }
                        val started = SystemClock.elapsedRealtime()
                        var passes = 1
                        val record = JSONObject().put("model", spec).put("chapter", case.slug)
                            .put("sourceWords", Fidelity.wordCount(case.source))
                        say("→ $spec · ${case.slug}")
                        runCatching {
                            RewritePipeline.rewrite(
                                settings, case.chapter.number(case.position), case.chapter.title, case.source,
                                previousNote = null, sheets = case.memory, previousTail = case.tail,
                                onStage = { if (it?.startsWith("REPAIRING") == true) passes++ },
                            )
                        }.fold(
                            onSuccess = { r ->
                                File(out, "${case.slug}.md").writeText(r.chapter)
                                File(out, "${case.slug}.note.md").writeText(r.note)
                                record.put("words", Fidelity.wordCount(r.chapter)).put("passes", passes)
                                    .put("warnings", r.note.substringAfter("## Fidelity warnings", "").lines().count { it.startsWith("- ") })
                                say("✓ $spec · ${case.slug} · ${record.getInt("words")} words, $passes pass")
                            },
                            onFailure = {
                                record.put("error", it.message ?: it.javaClass.simpleName)
                                say("✗ $spec · ${case.slug} · ${it.message}")
                            },
                        )
                        record.put("seconds", (SystemClock.elapsedRealtime() - started) / 1000)
                        synchronized(results) { results.appendText(record.toString() + "\n") }
                    }
                }
            }.awaitAll()
        }
        File(dir, "DONE").writeText(RewriteClient.PROMPT_VERSION)
        say("Done. Pull with: tools/lab/lab.py pull $run")
    }

    /** A chapter's text without its opening title, from the vault or else from the site. */
    private suspend fun text(project: Project, chapter: Chapter, vault: Vault): String {
        val blocks = withContext(Dispatchers.IO) { vault.read(project, chapter) }
            ?.let { MarkdownParser.parse(it, markdown = true) }
            ?: FreeWebNovelClient.loadChapter(chapter.uri)
        val body = if ((blocks.firstOrNull() as? Block.Heading)?.level?.let { it <= 2 } == true) blocks.drop(1) else blocks
        return MarkdownWriter.write(body)
    }

    companion object {
        /** One chapter of each kind: comedy, fight, inner thought, dialogue, system screens, crowd. */
        const val DEFAULT_CHAPTERS =
            "/novel/who-let-him-cultivate-wx/chapter-2," +
                "/novel/shadow-slave/chapter-5," +
                "/novel/immortality-through-array-formations/chapter-2," +
                "/novel/lord-of-mysteries-2-circle-of-inevitability/chapter-2," +
                "/novel/obtaining-10x-rewards-reincarnated-into-a-novel-as-a-side-character/chapter-3," +
                "/novel/shadow-slave/chapter-2"

        const val DEFAULT_MODELS =
            "gemma4:31b"
    }
}

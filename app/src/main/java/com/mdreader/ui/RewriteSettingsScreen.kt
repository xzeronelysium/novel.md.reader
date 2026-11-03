package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mdreader.data.RewriteClient
import com.mdreader.data.RewriteSettings
import kotlinx.coroutines.launch

/**
 * Full-page rewrite settings, status first: a card says whether a rewrite can run and how much of
 * this novel the memory has read, and each setting folds open below it. In the reader's own
 * language: hairlines, small uppercase labels, square shapes, one vermilion accent. Every change is
 * saved at once, so leaving the page is all it takes.
 */
@Composable
fun RewriteSettingsScreen(
    initial: RewriteSettings,
    memoryChapters: Int,
    /** One entry per chapter before the open one: whether the memory has read it. */
    earlierRead: List<Boolean>,
    onChange: (RewriteSettings) -> Unit,
    onRebuildMemory: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var settings by remember { mutableStateOf(initial) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var efforts by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusFailed by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var memory by remember { mutableIntStateOf(memoryChapters) }
    var readSoFar by remember { mutableStateOf(earlierRead) }
    var confirmRebuild by remember { mutableStateOf(false) }
    // What is unfinished opens by itself; the rest waits for a tap.
    var open by remember {
        mutableStateOf(
            when {
                !initial.signedIn -> setOf("connection")
                initial.model.isBlank() -> setOf("model")
                else -> emptySet()
            },
        )
    }
    fun toggle(section: String) {
        open = if (section in open) open - section else open + section
    }

    fun update(next: RewriteSettings) {
        settings = next
        onChange(next)
    }

    suspend fun effortsOf(current: RewriteSettings, id: String): List<String> =
        RewriteClient.efforts(current.baseUrl, current.apiKey, id)

    fun loadModels() {
        status = "Connecting"
        statusFailed = false
        scope.launch {
            val current = settings
            runCatching {
                RewriteClient.models(current.baseUrl, current.apiKey)
            }.fold(
                onSuccess = { list ->
                    models = list
                    status = "Connected · ${list.size} models"
                },
                onFailure = {
                    statusFailed = true
                    status = it.message?.lineSequence()?.first()?.take(110) ?: "Couldn't connect"
                },
            )
        }
    }

    fun pick(id: String) {
        update(settings.copy(model = id, effort = ""))
        picking = false
        query = ""
        efforts = emptyList()
        val current = settings
        scope.launch { efforts = effortsOf(current, id) }
    }

    LaunchedEffect(Unit) {
        if (settings.signedIn) {
            loadModels()
            if (settings.model.isNotBlank()) efforts = effortsOf(settings, settings.model)
        }
    }

    // Its own window, so it sits above the reader whatever the reader is laid out in.
    Dialog(
        onDismissRequest = { if (picking) picking = false else onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onBack)
                        .semantics {
                            contentDescription = "Back to chapter"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) { QuietArrow(direction = ArrowDirection.Left) }
                Micro(text = "Rewrite", modifier = Modifier.weight(1f).padding(end = 48.dp), color = scheme.onSurfaceVariant)
            }
            Hairline()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPad),
            ) {
                Spacer(Modifier.height(24.dp))
                // The chapters the next rewrite's memory would read, and how many of them it already has.
                val window = if (settings.memoryDepth <= 0) readSoFar else readSoFar.takeLast(settings.memoryDepth)
                StatusCard(
                    settings = settings,
                    connection = status,
                    connectionFailed = statusFailed,
                    memoryRead = window.count { it },
                    memoryTarget = window.size,
                )
                Spacer(Modifier.height(14.dp))

                Fold(
                    title = "Model and effort",
                    value = settings.model.ifBlank { "Choose one" } + if (settings.effort.isNotBlank()) " · ${settings.effort}" else "",
                    open = "model" in open,
                    onToggle = {
                        if ("model" !in open && models.isEmpty() && settings.signedIn) loadModels()
                        toggle("model")
                    },
                ) {
                    if (models.size > 8) LineField(label = "Search", value = query, onChange = { query = it })
                    // A short list shows whole; only a long one scrolls inside the page.
                    Column(if (models.size > 12) Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()) else Modifier) {
                        val shown = models.filter { it.contains(query.trim(), ignoreCase = true) }
                        shown.forEachIndexed { position, id ->
                            val selected = id == settings.model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pick(id) }
                                    .height(48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = (position + 1).toString().padStart(2, '0'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(30.dp),
                                )
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) scheme.primary else scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (position < shown.lastIndex) Hairline()
                        }
                        if (shown.isEmpty()) {
                            Micro(
                                text = when {
                                    !settings.signedIn -> "Add the API key under Connection first"
                                    else -> "No model matches"
                                },
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    if (efforts.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Micro(text = "Effort")
                        Spacer(Modifier.height(8.dp))
                        Segmented(
                            options = listOf("" to "Default") + efforts.map { it to it.replaceFirstChar(Char::uppercase) },
                            selected = settings.effort,
                            onSelect = { update(settings.copy(effort = it)) },
                        )
                        Note("How long the model thinks before it writes. Only the levels this model supports.")
                    } else if (settings.model.isNotBlank()) {
                        Note("This model has no effort levels to choose from.")
                    }
                }

                Fold(
                    title = "Memory",
                    value = when (settings.memoryDepth) {
                        0 -> "From chapter 1"
                        else -> "Last ${settings.memoryDepth}"
                    },
                    open = "memory" in open,
                    onToggle = { toggle("memory") },
                ) {
                    Segmented(
                        options = listOf("20" to "Last 20", "100" to "Last 100", "0" to "All"),
                        selected = settings.memoryDepth.toString(),
                        onSelect = { update(settings.copy(memoryDepth = it.toInt())) },
                    )
                    Note(
                        "People, places, world rules and the story so far, read from earlier chapters before a rewrite. " +
                            "The first read takes one call per chapter; after that only new chapters.",
                    )
                    ValueRow(
                        value = if (memory == 1) "1 chapter read" else "$memory chapters read",
                        action = if (confirmRebuild) "Tap again to clear" else "Rebuild",
                        enabled = memory > 0,
                        muted = memory == 0,
                    ) {
                        if (confirmRebuild) {
                            onRebuildMemory()
                            memory = 0
                            readSoFar = readSoFar.map { false }
                            confirmRebuild = false
                        } else {
                            confirmRebuild = true
                        }
                    }
                }

                Fold(
                    title = "Connection",
                    value = "Ollama Cloud",
                    open = "connection" in open,
                    onToggle = { toggle("connection") },
                ) {
                    LineField(
                        label = "Base URL",
                        value = settings.baseUrl,
                        onChange = { update(settings.copy(baseUrl = it)) },
                        keyboardType = KeyboardType.Uri,
                    )
                    LineField(
                        label = "API key",
                        value = settings.apiKey,
                        onChange = { update(settings.copy(apiKey = it)) },
                        mask = !showKey,
                        action = if (showKey) "Hide" else "Show",
                        onAction = { showKey = !showKey },
                    )
                    OutlineAction(
                        label = "Test connection",
                        enabled = settings.apiKey.isNotBlank(),
                        modifier = Modifier.padding(top = 16.dp),
                    ) { loadModels() }
                    Note("The key is sealed with the Android Keystore and only ever sent to this address.")
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/**
 * Whether a rewrite can run, and with what: a hairline box with a status line, the main choices,
 * and a bar for how much of this novel's memory has been read.
 */
@Composable
private fun StatusCard(
    settings: RewriteSettings,
    connection: String?,
    connectionFailed: Boolean,
    memoryRead: Int,
    memoryTarget: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val (headline, ready) = when {
        !settings.signedIn -> "Add an API key to start" to false
        connectionFailed -> (connection ?: "Couldn't connect") to false
        settings.model.isBlank() -> "Choose a model" to false
        else -> "Ready to rewrite" to true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                SealDot()
                Spacer(Modifier.width(8.dp))
            }
            Micro(text = headline, accent = ready, color = if (ready) scheme.primary else scheme.error)
        }
        Spacer(Modifier.height(10.dp))
        StatusLine("Service", "Ollama Cloud")
        StatusLine("Model", settings.model.ifBlank { "—" })
        if (settings.effort.isNotBlank()) StatusLine("Effort", settings.effort.replaceFirstChar(Char::uppercase))
        StatusLine(
            "Memory · this novel",
            if (memoryTarget == 0) "Nothing to read yet" else "$memoryRead of $memoryTarget chapters",
        )
        if (memoryTarget > 0) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(scheme.outlineVariant)) {
                Box(
                    Modifier
                        .fillMaxWidth((memoryRead.toFloat() / memoryTarget).coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(scheme.primary),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A section that folds: its name and current value on a hairline row, its controls below when open. */
@Composable
private fun Fold(title: String, value: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .height(54.dp)
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
            Text(
                if (open) "  –" else "  +",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) { content() }
        }
        Hairline()
    }
}

/**
 * Square segmented choice: the active segment filled in ink, the rest outlined. More than
 * [perRow] options wrap into a grid of equal cells, so a long list of levels never squeezes its
 * labels; a short last row is padded with empty cells to keep the columns aligned.
 */
@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    perRow: Int = 4,
    onSelect: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rows = options.size.let { n -> (n + perRow - 1) / perRow }.coerceAtLeast(1)
    // Fill the rows evenly: 7 options make rows of 4 and 3, not 4 and a lonely 3-wide gap.
    val columns = (options.size + rows - 1) / rows
    Column(Modifier.fillMaxWidth().border(1.dp, scheme.outline)) {
        options.chunked(columns).forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (c in 0 until columns) {
                    if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outline))
                    val option = row.getOrNull(c)
                    if (option == null) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val (value, label) = option
                    val on = value == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (on) scheme.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 4.dp, vertical = 12.dp)
                            .semantics { role = Role.RadioButton },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) scheme.background else scheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** A flat, square outlined action, like the library's import button. */
@Composable
private fun OutlineAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (enabled) scheme.onSurface else scheme.outlineVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** An underlined input: label above, text on a hairline that turns vermilion while typing. */
@Composable
private fun LineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    mask: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Micro(text = label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
                visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (action != null) {
                Text(
                    text = action.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier.clickable(onClick = onAction).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                )
            }
        }
        Hairline(color = if (focused) scheme.primary else scheme.outlineVariant)
    }
}

/** A value on the left, one small action on the right. */
@Composable
private fun ValueRow(value: String, action: String, enabled: Boolean, muted: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = action.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** Flat text choices, the same idiom as the reader's S / M / L. The active one is vermilion. */
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) scheme.primary else scheme.onSurfaceVariant,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier
                    .clickable { onSelect(value) }
                    .padding(end = 24.dp, top = 14.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

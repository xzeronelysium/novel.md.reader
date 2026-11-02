package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Full-page rewrite settings, in the reader's own language: hairlines, small uppercase labels,
 * flat text choices with the active one in vermilion. Every change is saved at once, so leaving
 * the page is all it takes.
 */
@Composable
fun RewriteSettingsScreen(
    initial: RewriteSettings,
    memoryChapters: Int,
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
    var confirmRebuild by remember { mutableStateOf(false) }

    fun update(next: RewriteSettings) {
        settings = next
        onChange(next)
    }

    fun loadModels() {
        status = "Connecting"
        statusFailed = false
        scope.launch {
            runCatching { RewriteClient.models(settings.baseUrl, settings.apiKey) }.fold(
                onSuccess = { models = it; status = "Connected · ${it.size} models" },
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
        scope.launch { efforts = RewriteClient.efforts(settings.baseUrl, settings.apiKey, id) }
    }

    LaunchedEffect(Unit) {
        if (settings.apiKey.isNotBlank()) {
            loadModels()
            if (settings.model.isNotBlank()) {
                efforts = RewriteClient.efforts(settings.baseUrl, settings.apiKey, settings.model)
            }
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
                Spacer(Modifier.height(30.dp))
                Micro(text = "Ollama Cloud", accent = true)
                Spacer(Modifier.height(8.dp))
                Text("Settings", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                Text(
                    "How a chapter is rewritten. Changes save as you make them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(26.dp))

                Section("01", "Connection") {
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "TEST CONNECTION",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.apiKey.isBlank()) scheme.onSurfaceVariant.copy(alpha = 0.4f) else scheme.onSurface,
                            modifier = Modifier
                                .clickable(enabled = settings.apiKey.isNotBlank()) { loadModels() }
                                .padding(vertical = 6.dp),
                        )
                        status?.let {
                            Spacer(Modifier.width(14.dp))
                            if (!statusFailed && it.startsWith("Connected")) SealDot()
                            if (!statusFailed && it.startsWith("Connected")) Spacer(Modifier.width(7.dp))
                            Micro(
                                text = it,
                                color = if (statusFailed) scheme.error else scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Section("02", "Model") {
                    ValueRow(
                        value = settings.model.ifBlank { "Choose a model" },
                        action = if (picking) "Close" else "Change",
                        enabled = models.isNotEmpty() || settings.apiKey.isNotBlank(),
                        muted = settings.model.isBlank(),
                    ) {
                        if (models.isEmpty()) loadModels()
                        picking = !picking
                    }
                    if (picking) {
                        Spacer(Modifier.height(6.dp))
                        LineField(label = "Search", value = query, onChange = { query = it })
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            val shown = models.filter { it.contains(query.trim(), ignoreCase = true) }
                            shown.forEachIndexed { position, id ->
                                val selected = id == settings.model
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pick(id) }
                                        .height(50.dp),
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
                                Micro(text = "No model matches", modifier = Modifier.padding(vertical = 16.dp))
                            }
                        }
                    }
                }

                if (efforts.isNotEmpty()) {
                    Section("03", "Effort") {
                        Choices(
                            options = listOf("" to "Default") + efforts.map { it to it.replaceFirstChar(Char::uppercase) },
                            selected = settings.effort,
                            onSelect = { update(settings.copy(effort = it)) },
                        )
                        Note("How long the model thinks before it writes. Only the levels this model supports.")
                    }
                }

                Section(if (efforts.isNotEmpty()) "04" else "03", "Strength") {
                    Choices(
                        options = listOf("light" to "Light", "full" to "Full", "max" to "Max"),
                        selected = settings.strength,
                        onSelect = { update(settings.copy(strength = it)) },
                    )
                    Note(
                        when (settings.strength) {
                            "light" -> "Clean-up. 1.0 to 1.2 times the source length, at least."
                            "max" -> "Deepest rewrite. 1.3 to 1.6 times the source length, at least."
                            else -> "Full rewrite. 1.15 to 1.45 times the source length, at least."
                        },
                    )
                }

                Section(if (efforts.isNotEmpty()) "05" else "04", "Memory", last = true) {
                    ValueRow(
                        value = if (memory == 1) "1 chapter read" else "$memory chapters read",
                        action = if (confirmRebuild) "Tap again to clear" else "Rebuild",
                        enabled = memory > 0,
                        muted = memory == 0,
                    ) {
                        if (confirmRebuild) {
                            onRebuildMemory()
                            memory = 0
                            confirmRebuild = false
                        } else {
                            confirmRebuild = true
                        }
                    }
                    Choices(
                        options = listOf("20" to "Last 20", "100" to "Last 100", "0" to "From chapter 1"),
                        selected = settings.memoryDepth.toString(),
                        onSelect = { update(settings.copy(memoryDepth = it.toInt())) },
                    )
                    Note(
                        "People, places, world rules and the story so far, gathered from earlier chapters before a rewrite. " +
                            "Reading from chapter 1 takes one call per chapter the first time, then only new chapters. " +
                            "Rebuild clears this novel's memory; the next rewrite reads them again.",
                    )
                }
                GridRule(Modifier.fillMaxWidth())
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** A numbered block: `01  CONNECTION`, a hairline, then its rows. */
@Composable
private fun Section(number: String, title: String, last: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(30.dp),
            )
            Micro(text = title, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        Hairline()
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(if (last) 28.dp else 30.dp))
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
        modifier = Modifier.padding(top = 2.dp),
    )
}

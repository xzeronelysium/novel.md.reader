package com.mdreader.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import com.mdreader.desktop.ui.DesktopApp

fun main() = application {
    val appIcon = painterResource("icon.png")
    Window(
        onCloseRequest = ::exitApplication,
        title = "novel.md.reader",
        icon = appIcon,
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        DesktopApp()
    }
}

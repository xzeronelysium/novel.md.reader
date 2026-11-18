package com.mdreader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.mdreader.ui.MDReaderApp
import com.mdreader.ui.theme.MDReaderTheme
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun main() {
    SwingUtilities.invokeLater {
        val window = ComposeWindow(null)
        window.title = "novel.md.reader"
        window.setSize(900, 700)
        window.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        window.setContent {
            MDReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MDReaderApp()
                }
            }
        }
        window.isVisible = true
    }
}

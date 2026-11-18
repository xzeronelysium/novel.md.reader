package com.mdreader.desktop.platform

import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

/**
 * Prefers the desktop's own folder dialog (portal/zenity/kdialog on Linux),
 * then falls back to a system-themed Swing chooser.
 */
object NativeFolderPicker {
    fun pickDirectory(title: String = "Import novel folder"): String? {
        if (isLinux()) {
            pickWithZenity(title)?.let { return it }
            pickWithKdialog(title)?.let { return it }
        }
        return pickWithSwing(title)
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").lowercase().contains("linux")

    private fun pickWithZenity(title: String): String? {
        if (!commandExists("zenity")) return null
        val process = ProcessBuilder(
            "zenity",
            "--file-selection",
            "--directory",
            "--title=$title",
        ).redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0 && path.isNotEmpty()) {
            val dir = File(path)
            if (dir.isDirectory) return dir.absolutePath
        }
        return null
    }

    private fun pickWithKdialog(title: String): String? {
        if (!commandExists("kdialog")) return null
        val home = System.getProperty("user.home")
        val process = ProcessBuilder(
            "kdialog",
            "--getexistingdirectory",
            home,
            "--title",
            title,
        ).redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0 && path.isNotEmpty()) {
            val dir = File(path)
            if (dir.isDirectory) return dir.absolutePath
        }
        return null
    }

    private fun pickWithSwing(title: String): String? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(FileSystemView.getFileSystemView()).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.absolutePath
    }

    private fun commandExists(name: String): Boolean {
        val process = ProcessBuilder("which", name).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return false
        return process.inputStream.bufferedReader().readText().isNotBlank()
    }
}

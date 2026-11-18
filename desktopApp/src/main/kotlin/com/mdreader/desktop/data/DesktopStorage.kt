package com.mdreader.desktop.data

import com.mdreader.data.LibraryStore
import java.io.File

object DesktopStorage {
    fun libraryStore(): LibraryStore {
        val config = configDirectory()
        config.mkdirs()
        return LibraryStore(File(config, "library.json"))
    }

    private fun configDirectory(): File {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        val base = if (!xdg.isNullOrBlank()) {
            File(xdg)
        } else {
            File(System.getProperty("user.home"), ".config")
        }
        return File(base, "novel.md.reader")
    }
}

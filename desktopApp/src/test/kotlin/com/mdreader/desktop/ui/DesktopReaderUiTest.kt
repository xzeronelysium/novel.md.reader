package com.mdreader.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mdreader.data.Chapter
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class DesktopReaderUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chapterSearchShowsMatchingRowsAndCount() {
        val query = mutableStateOf("")
        val selected = mutableStateOf(-1)
        compose.setContent {
            ChapterIndexRail(
                projectName = "Sample Novel",
                chapters = testChapters,
                currentIndex = selected.value,
                query = query.value,
                onQueryChange = { query.value = it },
                searchFocusRequester = FocusRequester(),
                onSearchFocusChange = {},
                onSelect = { selected.value = it },
            )
        }

        compose.onNode(hasSetTextAction()).performTextInput("Snow Country")
        compose.onNodeWithText("CH. 03").assertIsDisplayed()
        assertEquals(listOf(2), filterChapters(testChapters, "Snow Country").map { it.index })
    }

    private companion object {
        val testChapters = listOf(
            Chapter("chapter_1.md", "uri:1"),
            Chapter("chapter_2.txt", "uri:2"),
            Chapter("Snow_Country.md", "uri:3"),
        )
    }
}

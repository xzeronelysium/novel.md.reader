package com.mdreader

import com.mdreader.data.BatchRewrite
import com.mdreader.data.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRewriteTest {
    private val project = Project("p-1", "Shadow Slave", "", emptyList(), 0L)

    @Test fun theQueueIsTheRangeInOrderWithRewrittenChaptersSkippedOrRedone() {
        val done = setOf(2, 3)
        assertEquals(listOf(1, 4, 5), BatchRewrite.queueFor(1, 5, done, redo = false))
        assertEquals(listOf(1, 2, 3, 4, 5), BatchRewrite.queueFor(1, 5, done, redo = true))
        // A range typed the wrong way round is the same range.
        assertEquals(listOf(1, 4, 5), BatchRewrite.queueFor(5, 1, done, redo = false))
        assertEquals(emptyList<Int>(), BatchRewrite.queueFor(2, 3, done, redo = false))
        assertEquals(listOf(7), BatchRewrite.queueFor(7, 7, done, redo = false))
    }

    @Test fun aRunShowsItsProgressAndKeepsItsResultUntilCleared() {
        BatchRewrite.clearResult()
        assertEquals(false, BatchRewrite.state.value.running)

        BatchRewrite.begin(project, listOf(4, 5, 6))
        var state = BatchRewrite.state.value
        assertTrue(state.running)
        assertEquals(3, state.total)
        assertEquals(0, state.done)
        assertEquals("Shadow Slave", state.projectName)
        assertNull(state.result)

        BatchRewrite.update { it.copy(done = 1, currentLabel = "Chapter 5 · The Gate", stage = "WRITING SCENE 2/3") }
        state = BatchRewrite.state.value
        assertEquals(1, state.done)
        assertEquals("WRITING SCENE 2/3", state.stage)

        BatchRewrite.end("Stopped at Chapter 6: the key was refused. 2 of 3 done.", failed = true)
        state = BatchRewrite.state.value
        assertEquals(false, state.running)
        assertEquals("", state.stage)
        assertEquals("", state.currentLabel)
        assertTrue(state.failed)
        assertTrue(state.result!!.startsWith("Stopped at Chapter 6"))
        // The result stays for the screen to show, then goes away when put away.
        assertEquals(3, state.total)
        BatchRewrite.clearResult()
        assertNull(BatchRewrite.state.value.result)
        assertEquals(0, BatchRewrite.state.value.total)
    }

    @Test fun aRunInProgressCannotBeClearedByAccident() {
        BatchRewrite.begin(project, listOf(1))
        BatchRewrite.clearResult()
        assertTrue(BatchRewrite.state.value.running)
        BatchRewrite.end("Rewrote 1 chapter.")
        BatchRewrite.clearResult()
    }
}

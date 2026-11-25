package com.finalexam.to_do_list

import com.finalexam.to_do_list.controller.home.TrashRefresh
import com.finalexam.to_do_list.model.local.relation.TrashTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRefreshTest {
    @Test fun coalescesChangesWhileLoadingAndDisplaysLatestRoomResult() {
        val callbacks = ArrayDeque<(Result<List<TrashTask>>) -> Unit>()
        val shown = mutableListOf<List<TrashTask>>()
        val refresh = TrashRefresh({ true }, { callbacks.addLast(it) }, { shown.add(it.getOrThrow()) })
        val deleted = listOf(TrashTask("task", "Công việc", "Danh mục", 10L, "category"))

        refresh.refresh()
        repeat(3) { refresh.refresh() }
        assertEquals(1, callbacks.size)
        callbacks.removeFirst()(Result.success(deleted))
        assertTrue(shown.isEmpty())
        assertEquals(1, callbacks.size)
        callbacks.removeFirst()(Result.success(emptyList()))
        assertEquals(listOf(emptyList<TrashTask>()), shown)
    }

    @Test fun ignoresOldResultAfterPageOrSessionCloses() {
        val callbacks = ArrayDeque<(Result<List<TrashTask>>) -> Unit>()
        val shown = mutableListOf<List<TrashTask>>()
        var current = true
        val refresh = TrashRefresh({ current }, { callbacks.addLast(it) }, { shown.add(it.getOrThrow()) })

        refresh.refresh()
        refresh.refresh()
        current = false
        callbacks.removeFirst()(Result.success(emptyList()))
        refresh.refresh()
        assertTrue(shown.isEmpty())
        assertTrue(callbacks.isEmpty())

        current = true
        refresh.refresh()
        callbacks.removeFirst()(Result.success(emptyList()))
        assertEquals(1, shown.size)
    }
}

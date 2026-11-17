package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GuestTaskRepositoryTest : DatabaseTestBase() {
    private fun repository() = GuestTaskRepository(database, Executor { it.run() }, { it() })
    private fun rows(repo: GuestTaskRepository): List<HomeTask> {
        var result: Result<List<HomeTask>>? = null
        repo.load { result = it }
        return result!!.getOrThrow()
    }
    private fun success(operation: ((Result<Unit>) -> Unit) -> Unit) {
        var result: Result<Unit>? = null
        operation { result = it }
        result!!.getOrThrow()
    }

    @Test fun guestCrudUsesNormalizedTablesAndSurvivesReopenWithoutAnAccount() {
        var repo = repository()
        success { repo.save(null, "  Offline work  ", " Notes ", "Học tập", 3, 500L, it) }
        val saved = rows(repo).single()
        assertEquals("Offline work", saved.title)
        assertEquals("Notes", saved.description)
        assertEquals("Học tập", saved.category)
        assertTrue(database.tasks().find(saved.id)!!.createdByUid.startsWith("local:"))
        success { repo.save(saved.id, "Edited", "", "Học tập", 1, null, it) }
        assertEquals(saved.id, rows(repo).single().id)
        assertEquals(saved.createdAt, rows(repo).single().createdAt)
        success { repo.toggle(saved.id, it) }
        assertTrue(rows(repo).single().done)
        assertNotNull(rows(repo).single().completedAt)
        reopen(); repo = repository()
        assertTrue(rows(repo).single().done)
        success { repo.toggle(saved.id, it) }
        assertFalse(rows(repo).single().done)
        assertNull(rows(repo).single().completedAt)
        success { repo.delete(saved.id, it) }
        assertTrue(rows(repo).isEmpty())
        assertEquals(0, count("sync_queue"))
        assertEquals(0, count("sync_state"))
    }

    @Test fun invalidOrUnauthorizedWritesFailAndNeverCreateOrResurrectRows() {
        val repo = repository()
        var result: Result<Unit>? = null
        repo.save(null, "  ", "", "Cá nhân", 2, null) { result = it }
        assertTrue(result!!.isFailure)
        repo.save("missing", "Task", "", "Cá nhân", 2, null) { result = it }
        assertTrue(result!!.isFailure)
        repo.save(null, "Task", "", "Cá nhân", 4, null) { result = it }
        assertTrue(result!!.isFailure)
        execute("UPDATE category_members SET role='VIEWER' WHERE category_id=?", category)
        repo.save(null, "Task", "", "Cá nhân", 2, null) { result = it }
        assertTrue(result!!.isFailure)
        assertEquals(0, count("tasks"))
    }
}

package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.WorkspaceMember
import com.finalexam.to_do_list.model.repository.cachedMembers
import com.finalexam.to_do_list.model.repository.businessCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceMembersTest : DatabaseTestBase() {
    @Test fun cachedList() {
        user("editor")
        user("removed")
        user("other")
        member("editor")
        member("removed", status = "REMOVED")
        member("other", categoryId = study)
        execute("UPDATE users SET full_name='',email='editor@example.test' WHERE uid='editor'")
        execute("UPDATE category_members SET revision=3 WHERE user_uid='editor'")

        val owner = AccountWorkspace(uid, database)
        val list = owner.cachedMembers(category)
        assertEquals("OWNER", list.role)
        assertFalse(list.synced)
        assertEquals(setOf(uid, "editor"), list.members.map { it.uid }.toSet())
        assertEquals(WorkspaceMember("editor", "editor@example.test", "EDITOR", 3),
            list.members.first { it.uid == "editor" })

        execute("UPDATE categories SET revision=1 WHERE id=?", category)
        assertEquals("EDITOR", AccountWorkspace("editor", database).cachedMembers(category).role)
        assertTrue(owner.cachedMembers(category).synced)
        assertEquals("PERMISSION_DENIED", runCatching { AccountWorkspace("removed", database).cachedMembers(category) }
            .exceptionOrNull()?.businessCode())
    }
}

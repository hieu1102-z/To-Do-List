package com.finalexam.to_do_list

import com.finalexam.to_do_list.model.repository.CategorySyncSignal
import com.finalexam.to_do_list.model.repository.WorkspaceSyncDemand
import com.finalexam.to_do_list.model.repository.WorkspaceSyncInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSyncInboxTest {
    @Test fun workManagerAndForegroundSignalsMergeIntoOneFollowUpPass() {
        val inbox = WorkspaceSyncInbox()
        inbox.offer(WorkspaceSyncDemand(discover = true))
        assertEquals(WorkspaceSyncDemand(discover = true), inbox.take())

        inbox.offer(WorkspaceSyncDemand(reconnected = true))
        inbox.offer(WorkspaceSyncDemand(repair = true, categories = mapOf("a" to CategorySyncSignal(2, false))))
        inbox.offer(WorkspaceSyncDemand(categories = mapOf("a" to CategorySyncSignal(3, true))))
        val followUp = checkNotNull(inbox.take())

        assertTrue(followUp.reconnected)
        assertTrue(followUp.repair)
        assertEquals(mapOf("a" to CategorySyncSignal(3, true)), followUp.categories)
        assertFalse(inbox.hasPending)
        assertNull(inbox.take())
    }
}

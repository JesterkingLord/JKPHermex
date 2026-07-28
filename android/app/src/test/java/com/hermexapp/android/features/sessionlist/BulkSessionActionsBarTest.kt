package com.hermexapp.android.features.sessionlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkSessionActionsBarTest {

    @Test
    fun `bulk mutations are disabled without selected sessions`() {
        assertFalse(bulkMutationEnabled(0))
        assertFalse(bulkMutationEnabled(-1))
    }

    @Test
    fun `bulk mutations are enabled with a selected session`() {
        assertTrue(bulkMutationEnabled(1))
        assertTrue(bulkMutationEnabled(20))
    }
}

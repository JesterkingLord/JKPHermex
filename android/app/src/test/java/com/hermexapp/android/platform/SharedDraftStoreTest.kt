package com.hermexapp.android.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedDraftStoreTest {

    @Test
    fun `offer trims text and drops empty shares`() {
        val store = SharedDraftStore()
        store.offer("   ")
        assertNull(store.pending.value)

        store.offer("  hello  ")
        assertEquals("hello", store.pending.value?.text)
    }

    @Test
    fun `carries multiple shared file uris`() {
        val store = SharedDraftStore()
        store.offer(text = null, fileUris = listOf("content://a", "content://b"))
        val content = store.pending.value
        assertEquals(listOf("content://a", "content://b"), content?.fileUris)
        assertTrue(content?.text.isNullOrEmpty())
    }

    @Test
    fun `consume returns and clears the pending share`() {
        val store = SharedDraftStore()
        store.offer("hi", listOf("content://x"))
        val consumed = store.consume()
        assertEquals("hi", consumed?.text)
        assertEquals(listOf("content://x"), consumed?.fileUris)
        assertNull(store.pending.value)
    }

    @Test
    fun `conditional consume never clears a newer pending share`() {
        val store = SharedDraftStore()
        store.offer("first")
        val first = requireNotNull(store.pending.value)

        store.offer("second")

        assertNull(store.consume(first))
        assertEquals("second", store.pending.value?.text)
    }

    @Test
    fun `conditional consume clears the matching pending share`() {
        val store = SharedDraftStore()
        store.offer("retry-safe")
        val expected = requireNotNull(store.pending.value)

        assertEquals(expected, store.consume(expected))
        assertNull(store.pending.value)
    }
}

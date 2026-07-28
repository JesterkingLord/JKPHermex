package com.hermexapp.android.features.prompts

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTagsTest {

    @Test
    fun `tag labels are trimmed and empty entries are ignored`() {
        assertEquals(
            listOf("writing", "summary", "long tag"),
            parsePromptTags(" writing, ,summary, long tag "),
        )
    }
}

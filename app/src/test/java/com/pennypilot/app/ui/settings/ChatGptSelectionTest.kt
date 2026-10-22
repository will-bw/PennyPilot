package com.pennypilot.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class ChatGptSelectionTest {
    @Test fun `account switch cannot save another accounts model catalog`() {
        assertNotNull(chatGptSelectionError(true, true, "account-b", "account-a", false, listOf("model"), "model"))
    }
    @Test fun `identity sign in without plan scope cannot be enabled`() {
        assertNotNull(chatGptSelectionError(true, false, "account", "account", false, listOf("model"), "model"))
    }
    @Test fun `previous model removed by server must be selected again`() {
        assertNotNull(chatGptSelectionError(true, true, "account", "account", false, listOf("available"), "removed"))
    }
    @Test fun `authorized account and current catalog can be saved`() {
        assertNull(chatGptSelectionError(true, true, "account", "account", false, listOf("available"), "available"))
    }
}

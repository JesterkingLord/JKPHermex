package com.hermexapp.android.features.chat

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural regression test for the v0.6.1 grey-band fix.
 *
 * Root cause recap (so the next person doesn't have to re-derive it):
 *   * `MainActivity.enableEdgeToEdge()` is on.
 *   * `ChatScreen`'s Scaffold applied `.imePadding()` on its root modifier
 *     (which already pushed the entire scaffold up by IME inset).
 *   * M3 Scaffold defaults its `contentWindowInsets` to `WindowInsets.systemBars`,
 *     so the lambda-received `innerPadding` contained a residual nav-bar bottom
 *     inset even when the IME was up — that residual inset rendered as a visible
 *     "grey band" between the composer and the keyboard.
 *   * Fix: zero out `contentWindowInsets` and keep `.imePadding()` as the single
 *     source of truth for the IME inset.
 *
 * Without bringing in Robolectric / compose-ui-test (≈200 MB of extra deps for
 * one assertion), this test reads the source file via the well-known
 * `src/main/java` path relative to the test class and asserts the exact
 * literals are present. It is a structural sentinel: any future refactor
 * that removes the fix will fail this test with a clear message rather than
 * shipping the regression to the phone.
 *
 * If this test starts failing on a fresh checkout, the answer is "the source
 * layout moved"; update [resolveChatScreenSource] to the new path rather than
 * deleting the test.
 */
class ChatScreenImeInsetsLayoutTest {

    @Test
    fun `chat Scaffold sets contentWindowInsets to all-zero so imePadding is the single inset source`() {
        val source = resolveChatScreenSource().readText()
        // M3 Scaffold defaults `contentWindowInsets` to WindowInsets.systemBars
        // when omitted; the bottom-half of that inset leaks through innerPadding
        // as a visible "grey band" under the keyboard. The fix is to set it
        // explicitly to zero so the Scaffold's innerPadding no longer adds the
        // nav-bar reservation on top of the already-applied IME padding.
        assertNotNull(
            "ChatScreen.kt must explicitly set contentWindowInsets on its Scaffold. " +
                "Without it, M3 defaults to WindowInsets.systemBars and a residual " +
                "nav-bar inset renders as a grey band between the composer and " +
                "the keyboard when the IME is up.",
            REGEX_CONTENT_WINDOW_INSETS_ZERO.find(source),
        )
        // The exact zero literal — `WindowInsets(0, 0, 0, 0)` — is what makes
        // the test a structural sentinel: an attempt to revert to the default
        // (by deleting the line) will fail this assertion; an attempt to set a
        // half-fix (e.g. `WindowInsets(0)` for top-only) will fail the regex.
        assertTrue(
            "ChatScreen.kt must contain the literal `WindowInsets(0, 0, 0, 0)` " +
                "on the contentWindowInsets argument so all four sides are zeroed. " +
                "Half-fixes (e.g. only zeroing the top) will still leak the " +
                "nav-bar inset and bring the grey band back.",
            REGEX_LITERAL_WINDOW_INSETTS_ZERO.containsMatchIn(source),
        )
    }

    @Test
    fun `chat Scaffold root modifier still applies imePadding`() {
        // We removed the *system-bar* contentWindowInsets; we kept `.imePadding()`
        // on the Scaffold modifier as the single source of truth for the IME
        // inset. If a future refactor deletes the modifier call, the composer
        // would slide down behind the keyboard.
        assertTrue(
            "ChatScreen.kt Scaffold modifier must still call `.imePadding()` — " +
                "the grey-band fix relies on it being the *only* IME-related " +
                "padding. Removing it would break keyboard handling entirely.",
            REGEX_IME_PADDING_ON_SCAFFOLD.containsMatchIn(resolveChatScreenSource().readText()),
        )
    }

    @Test
    fun `chat Screen does not double-apply systemBarsPadding inside its body`() {
        // Now that the Scaffold's contentWindowInsets is zeroed, anything that
        // *also* calls `WindowInsets.systemBars`/`navigationBarsPadding` inside
        // the body would re-introduce the very band we just removed.
        //
        // We only check the inner `Scaffold { innerPadding -> ... }` body
        // (everything after `innerPadding ->`), to allow unrelated composers in
        // the same file to use system-bar modifiers if they need.
        val source = resolveChatScreenSource().readText()
        val bodyStart = source.indexOf("innerPadding ->")
        require(bodyStart >= 0) {
            "ChatScreen.kt structure changed: the Scaffold's `innerPadding ->` " +
                "lambda could not be located. Update this test if the layout was " +
                "intentionally restructured."
        }
        val bodyAfterLambda = source.substring(bodyStart + "innerPadding ->".length)
        assertTrue(
            "After zeroing ChatScreen's Scaffold contentWindowInsets, no inner " +
                "composable should re-introduce system-bar insets. Found " +
                "`systemBars` reference inside the Scaffold body — this would " +
                "re-create the grey band. Use `HermexHeader.statusBarsPadding()` " +
                "for the top and rely on `.imePadding()` for the bottom instead.",
            !bodyAfterLambda.contains("systemBars"),
        )
        assertTrue(
            "After zeroing ChatScreen's Scaffold contentWindowInsets, no inner " +
                "composable should add navigation-bar padding. Found " +
                "`navigationBarsPadding` reference inside the Scaffold body — " +
                "this would push the composer *up* by the nav-bar height even " +
                "when the IME is up, re-creating the band.",
            !bodyAfterLambda.contains("navigationBarsPadding"),
        )
    }

    private companion object {
        /**
         * Walk up from this class's runtime location to a stable path that
         * points at `ChatScreen.kt`. Strategy:
         *   1. Env-var override (`HERMEX_CHATSCREEN_SOURCE_PATH`).
         *   2. Walk up from the class's protection-domain location until we find
         *      the matching `.../src/main/java/com/hermexapp/.../ChatScreen.kt`.
         *
         * In a standard Android gradle layout the test class lives under
         * `<module>/build/.../classes/...`; we walk up to `<module>/` then look
         * in `src/main/java/`. This is brittle-by-design: the test's whole
         * purpose is to be a structural sentinel, so a clean failure when the
         * layout moves is exactly what we want.
         */
        fun resolveChatScreenSource(): File {
            val override = System.getenv("HERMEX_CHATSCREEN_SOURCE_PATH")
            if (override != null) {
                val f = File(override)
                require(f.isFile) { "HERMEX_CHATSCREEN_SOURCE_PATH=$override does not exist." }
                return f
            }
            val classUrl = ChatScreenImeInsetsLayoutTest::class.java.protectionDomain
                ?.codeSource?.location?.toString()
                ?: ""
            val seed: File = when {
                classUrl.startsWith("file:") -> File(classUrl.removePrefix("file:"))
                else -> File(".").absoluteFile
            }
            // For each ancestor of the seed, look for the well-known relative
            // path; the first hit wins.
            var dir: File? = seed
            while (dir != null) {
                val candidate = File(
                    dir,
                    "src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt",
                )
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error(
                "Could not locate ChatScreen.kt — walked up from $seed looking " +
                    "for `src/main/java/com/hermexapp/android/features/chat/" +
                    "ChatScreen.kt` and never found a hit. Set " +
                    "HERMEX_CHATSCREEN_SOURCE_PATH to the absolute file path to override.",
            )
        }

        // `Scaffold(...modifier = Modifier.fillMaxSize().imePadding(),` precedes
        // the new `contentWindowInsets = WindowInsets(0, 0, 0, 0),` line; we
        // just need to confirm both are still present in that order.
        val REGEX_IME_PADDING_ON_SCAFFOLD =
            Regex("""Scaffold\s*\(\s*modifier\s*=\s*Modifier\.fillMaxSize\(\)\.imePadding\(\)""")
        val REGEX_CONTENT_WINDOW_INSETS_ZERO =
            Regex("""contentWindowInsets\s*=\s*WindowInsets\s*\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)""")
        val REGEX_LITERAL_WINDOW_INSETTS_ZERO =
            Regex("""WindowInsets\s*\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)""")
    }
}

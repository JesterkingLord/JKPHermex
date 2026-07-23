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

    @Test
    fun `MainActivity enables edge-to-edge with both system bars fully transparent`() {
        // The second half of the grey-band fix. `enableEdgeToEdge()` without
        // arguments draws a scrim under the navigation bar that shows through
        // the gap between the IME-pushed-up composer and the keyboard's top
        // edge — that's the grey band users have been seeing. Forcing both
        // bars' auto() scrims to TRANSPARENT removes the scrim entirely so
        // the chat canvas (or the keyboard, when up) claims the space.
        val source = resolveMainActivitySource().readText()
        assertTrue(
            "MainActivity.kt must call `enableEdgeToEdge(...)` with explicit\n" +
                "`statusBarStyle` and `navigationBarStyle` arguments. The\n" +
                "no-argument default applies a default scrim under both bars,\n" +
                "which draws the grey band when the IME is up.",
            REGEX_ENABLE_EDGE_TO_EDGE_WITH_BOTH_STYLES.containsMatchIn(source),
        )
        // Both bars should be transparent for both light and dark variants.
        val callText = REGEX_ENABLE_EDGE_TO_EDGE_BLOCK.find(source)?.value
            ?: error(
                "MainActivity.kt structure changed: the `enableEdgeToEdge(...)` " +
                    "call could not be located.",
            )
        val transparentCount = TRANSPARENT_TOKEN.findAll(callText).count()
        assertTrue(
            "The `enableEdgeToEdge(...)` block must contain exactly " +
                "$EXPECTED_TRANSPARENT_COUNT `Color.TRANSPARENT` references " +
                "(lightScrim + darkScrim for status, plus the same for " +
                "navigation). Found $transparentCount. A half-fix leaves the " +
                "scrim partially in place — use " +
                "`SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)` " +
                "for BOTH bars to kill the grey band.",
            transparentCount == EXPECTED_TRANSPARENT_COUNT,
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

        /**
         * Walk up from the test class's runtime location to
         * `MainActivity.kt`. Identical strategy to [resolveChatScreenSource]
         * — kept as a separate method so the two resolvers can drift if either
         * source moves to a new package, without having to bisect.
         */
        fun resolveMainActivitySource(): File {
            val override = System.getenv("HERMEX_MAINACTIVITY_SOURCE_PATH")
            if (override != null) {
                val f = File(override)
                require(f.isFile) { "HERMEX_MAINACTIVITY_SOURCE_PATH=$override does not exist." }
                return f
            }
            val classUrl = ChatScreenImeInsetsLayoutTest::class.java.protectionDomain
                ?.codeSource?.location?.toString()
                ?: ""
            val seed: File = when {
                classUrl.startsWith("file:") -> File(classUrl.removePrefix("file:"))
                else -> File(".").absoluteFile
            }
            var dir: File? = seed
            while (dir != null) {
                val candidate = File(dir, "src/main/java/com/hermexapp/android/MainActivity.kt")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error(
                "Could not locate MainActivity.kt — walked up from $seed " +
                    "looking for `src/main/java/com/hermexapp/android/MainActivity.kt` and " +
                    "never found a hit. Set HERMEX_MAINACTIVITY_SOURCE_PATH to the absolute " +
                    "file path to override.",
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

        // Detects the structural presence of an enableEdgeToEdge(...) call
        // that explicitly passes both a status-bar style and a navigation-bar
        // style. `enableEdgeToEdge()` with no args would NOT match this
        // regex, which is the whole point. We use `[\s\S]` (not `.`) so the
        // pattern crosses newlines and embedded `)` characters inside the
        // `SystemBarStyle.auto(...)` arguments.
        val REGEX_ENABLE_EDGE_TO_EDGE_WITH_BOTH_STYLES =
            Regex(
                """enableEdgeToEdge\s*\([\s\S]*?statusBarStyle\s*=[\s\S]*?navigationBarStyle\s*=[\s\S]*?\n\s*\)""",
            )
        // Captures the entire enableEdgeToEdge(...) call (multi-line) so we
        // can count `Color.TRANSPARENT` references inside it. The closing
        // paren is followed by a newline at the call's own indentation in
        // `MainActivity.onCreate` (8 spaces) — `\n        \)` matches.
        val REGEX_ENABLE_EDGE_TO_EDGE_BLOCK =
            Regex("""enableEdgeToEdge\s*\([\s\S]*?\n        \)\s""")
        // Counts each `Color.TRANSPARENT` literal occurrence.
        val TRANSPARENT_TOKEN = Regex("""Color\.TRANSPARENT""")
        // lightScrim + darkScrim for statusBarStyle + the same for
        // navigationBarStyle = 4 occurrences total.
        const val EXPECTED_TRANSPARENT_COUNT = 4
    }
}

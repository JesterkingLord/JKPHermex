package com.hermexapp.android.ui.markdown

/**
 * G-lite math rendering — a Unicode-symbol pass over inline (`$...$`)
 * and display (`$$...$$`) math regions.
 *
 * This is the honest, no-WebView, no-extra-deps tier. It does NOT
 * attempt to lay out fractions, roots, or matrices — those would
 * require either KaTeX (heavy) or a Compose-native math layout
 * (week-long project). What it does:
 *
 * 1. Find `$...$` and `$$...$$` regions in the source.
 * 2. Inside each region, replace common LaTeX commands with their
 *    Unicode equivalents (\alpha → α, \int → ∫, \sum → Σ, etc.).
 * 3. Replace `\frac{a}{b}` with `a/b` (the universal-fallback form).
 * 4. Replace superscript and subscript runs: `x^2` → `x²`, `x_n` → `xₙ`,
 *    `x_{i+1}` → `xᵢ₊₁`, `x^2_n` → `x²ₙ` (sub-sup interleaved).
 *
 * The output is plain text that renders identically with our existing
 * Markdown pipeline — the caller passes the result to the same
 * `inlineAnnotated` parser for bold/italic/code. The math regions are
 * wrapped in a tagged span so the renderer can apply a distinct style
 * (italic + serif font) to make them visually different from prose.
 */
object MathLite {

    /**
     * Result of a math-region replacement. [annotated] is the
     * rendered text; [isDisplay] distinguishes block math (`$$...$$`)
     * from inline math (`$...$`).
     */
    data class Region(
        val start: Int,
        val end: Int,          // exclusive
        val annotated: String,
        val isDisplay: Boolean,
    )

    /**
     * Find all math regions in [source] and return them sorted by start
     * position. Display math (`$$...$$`) is checked first so it
     * shadows inline math at the same position.
     */
    fun findRegions(source: String): List<Region> {
        val regions = mutableListOf<Region>()

        // 1) Display math: $$...$$ (must not span a line containing a
        //    bare '$' — typical LLM output has them paired on one line).
        val displayRegex = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
        displayRegex.findAll(source).forEach { m ->
            val body = m.groupValues[1].trim()
            if (body.isNotEmpty()) {
                regions.add(Region(m.range.first, m.range.last + 1, renderBody(body), isDisplay = true))
            }
        }

        // 2) Inline math: $...$ but NOT $$ (the display regex above
        //    consumed any $$ runs). The lookahead/lookbehind avoids
        //    matching currency-style "$5" or "$ var".
        val inlineRegex = Regex("""(?<!\$)\$(?!\$)([^\$\n]+?)(?<!\$)\$(?!\$)""")
        // Common English words that should never appear inside a
        // math region. LLM output phrases these naturally between
        // currency / variable references like "$5 and got $3 back".
        // If a $...$ body contains any of these, it's prose, not math.
        val proseWords = setOf(
            "and", "the", "for", "with", "from", "into", "that", "this",
            "you", "your", "have", "has", "had", "are", "was", "were",
            "but", "not", "can", "may", "got", "paid", "back", "got",
            "into", "out", "all", "any", "few", "one", "two", "three",
            "first", "last", "next", "than", "then", "when", "where",
        )
        inlineRegex.findAll(source).forEach { m ->
            val rawBody = m.groupValues[1]
            val body = rawBody.trim()
            // Heuristic: a body is math-shaped if it contains at
            // least one of: a backslash command, a superscript/
            // subscript marker, a Greek letter, a single letter
            // (variable), or starts with a known math punctuation.
            // The body must ALSO not contain common English words
            // (which would indicate prose between currency / variable
            // references rather than a math expression).
            val hasMathMarker = body.contains('\\') ||
                body.contains('^') ||
                body.contains('_') ||
                body.contains(Regex("""[α-ωΑ-Ω]""")) ||
                body.contains(Regex("""[A-Za-z]""")) ||
                body.firstOrNull() in setOf('=', '(', '[', '|', '<')
            val tokens = body.lowercase()
                .split(Regex("""[^a-z]+"""))
                .filter { it.isNotEmpty() }
            val hasProse = tokens.any { it in proseWords }
            if (hasMathMarker && !hasProse && body.isNotEmpty()) {
                regions.add(Region(m.range.first, m.range.last + 1, renderBody(body), isDisplay = false))
            }
        }

        return regions.sortedBy { it.start }
    }

    /**
     * Replace every math region in [source] with a tagged form that
     * the inline renderer can pick up: `⟦display:body⟧` or
     * `⟦inline:body⟧`. The triple-character delimiters are chosen to
     * not collide with any markdown syntax we already parse.
     *
     * The renderer sees the tagged string as a single token in the
     * `inlineAnnotated` walk; it strips the tag and applies the
     * math style.
     */
    fun replaceWithTags(source: String): String {
        val sb = StringBuilder(source)
        val regions = findRegions(source)
        // Walk back-to-front so earlier indices stay valid.
        for (r in regions.reversed()) {
            val tag = if (r.isDisplay) "⟦display:${r.annotated}⟧"
                      else "⟦inline:${r.annotated}⟧"
            sb.replace(r.start, r.end, tag)
        }
        return sb.toString()
    }

    // ── Body rendering ───────────────────────────────────────────

    /**
     * Render a math body. The order of operations matters:
     *  1. Replace `\frac{a}{b}` (and friends) — must come first
     *     because the body inside is itself math.
     *  2. Replace `\command` tokens with Unicode glyphs.
     *  3. Convert `^...` and `_...` runs into Unicode sup / sub.
     *  4. Collapse whitespace, strip spaces around math glyphs.
     *  5. Strip leftover braces.
     */
    internal fun renderBody(body: String): String {
        var s = body
        // 1. Fractions / binomial / stackrel / overset / underset.
        s = convertFractions(s)
        // 2. \command → Unicode.
        s = replaceCommands(s)
        // 3. Sup / sub runs.
        s = convertSubSup(s)
        // 4. Whitespace cleanup.
        // Math typesetting convention (and LaTeX math mode) collapses
        // inter-token whitespace — "\pi r^2" renders as "πr²", not
        // "π r^2". LLMs emit spaces to be human-readable; in math mode
        // we want the dense form. This applies AFTER every other
        // transformation so the operator/operand structure is already
        // resolved. We match Unicode whitespace explicitly so that
        // \, (U+2009 thin space) and \quad (U+2003 em space) also
        // collapse — Java's default \s is ASCII-only.
        s = s.replace(Regex("""[\s\u00A0\u2000-\u200A\u202F\u205F\u3000]+"""), "")
        s = s.replace(Regex("""[{}]"""), "")
        return s
    }

    private fun convertFractions(s: String): String {
        // \frac{...}{...} — recursive via a small loop; the inner parts
        // can themselves contain \frac so we re-run a few times.
        val fracRegex = Regex("""\\frac\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""")
        var out = s
        repeat(4) {
            out = fracRegex.replace(out) { m ->
                val top = renderBody(m.groupValues[1])
                val bot = renderBody(m.groupValues[2])
                // Wrap with parens so the slash doesn't get
                // re-interpreted as a division chain (a/b/c is
                // ambiguous; (a/b)/c is clear). If the top or bottom
                // is a single token, drop the wrapping parens.
                val topWrapped = if (top.contains(' ') || top.contains('+') ||
                                     top.contains('-') || top.contains('/')) "($top)" else top
                val botWrapped = if (bot.contains(' ') || bot.contains('+') ||
                                     bot.contains('-') || bot.contains('/')) "($bot)" else bot
                "$topWrapped/$botWrapped"
            }
        }
        // \dfrac, \tfrac → same shape.
        out = out.replace(Regex("""\\[dt]frac\b"""), """\frac""")
        // \binom{a}{b} → (a choose b) — no Unicode form.
        out = out.replace(Regex("""\\binom\s*\{([^{}]*)\}\s*\{([^{}]*)\}"""), "(C($1,$2))")
        return out
    }

    private fun convertSubSup(s: String): String {
        // ^N where N is a single char.
        var out = s
        // Multi-char sub: _{...} → group & convert.
        // The "openPat" is the literal two-character sequence: an
        // underscore (or caret) followed by a left brace. In a Kotlin
        // raw string each character is literal — there's no regex
        // escaping at this layer (indexOf doesn't interpret regex).
        out = convertSubSupBraced(out, "_{", closing = "}", subscript = true)
        out = convertSubSupBraced(out, "^{", closing = "}", subscript = false)
        // Single-char sub / sup.
        out = Regex("""_([0-9A-Za-z\-+=()\u00B0\u00B9\u00B2\u00B3])""").replace(out) { m ->
            charToSubscript(m.groupValues[1]) ?: m.value
        }
        out = Regex("""\^([0-9A-Za-z\-+=()\u00B0\u00B9\u00B2\u00B3])""").replace(out) { m ->
            charToSuperscript(m.groupValues[1]) ?: m.value
        }
        return out
    }

    private fun convertSubSupBraced(
        s: String,
        openPat: String,
        closing: String,
        subscript: Boolean,
    ): String {
        // Find all matches of `_{` or `^{`, then scan for the matching
        // closing brace (no nested-brace support beyond one level — the
        // LaTeX this app will see is flat, e.g. `x_{i+1}`).
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf(openPat, i)
            if (open < 0) { out.append(s, i, s.length); break }
            out.append(s, i, open)
            val bodyStart = open + openPat.length
            val close = s.indexOf(closing, bodyStart)
            if (close < 0) { out.append(s, open, s.length); break }
            val body = s.substring(bodyStart, close)
            val rendered = body.map { c ->
                if (subscript) charToSubscript(c.toString()) ?: c.toString()
                else charToSuperscript(c.toString()) ?: c.toString()
            }.joinToString("")
            out.append(rendered)
            i = close + 1
        }
        return out.toString()
    }

    // ── \command → Unicode ───────────────────────────────────────

    private val COMMAND_MAP: Map<String, String> = buildMap {
        // Greek lowercase
        put("\\alpha", "α"); put("\\beta", "β"); put("\\gamma", "γ"); put("\\delta", "δ")
        put("\\epsilon", "ε"); put("\\varepsilon", "ε"); put("\\zeta", "ζ"); put("\\eta", "η")
        put("\\theta", "θ"); put("\\vartheta", "ϑ"); put("\\iota", "ι"); put("\\kappa", "κ")
        put("\\lambda", "λ"); put("\\mu", "μ"); put("\\nu", "ν"); put("\\xi", "ξ")
        put("\\omicron", "ο"); put("\\pi", "π"); put("\\varpi", "ϖ"); put("\\rho", "ρ")
        put("\\varrho", "ϱ"); put("\\sigma", "σ"); put("\\varsigma", "ς"); put("\\tau", "τ")
        put("\\upsilon", "υ"); put("\\phi", "φ"); put("\\varphi", "ϕ"); put("\\chi", "χ")
        put("\\psi", "ψ"); put("\\omega", "ω")
        // Greek uppercase
        put("\\Alpha", "Α"); put("\\Beta", "Β"); put("\\Gamma", "Γ"); put("\\Delta", "Δ")
        put("\\Epsilon", "Ε"); put("\\Zeta", "Ζ"); put("\\Eta", "Η"); put("\\Theta", "Θ")
        put("\\Iota", "Ι"); put("\\Kappa", "Κ"); put("\\Lambda", "Λ"); put("\\Mu", "Μ")
        put("\\Nu", "Ν"); put("\\Xi", "Ξ"); put("\\Omicron", "Ο"); put("\\Pi", "Π")
        put("\\Rho", "Ρ"); put("\\Sigma", "Σ"); put("\\Tau", "Τ"); put("\\Upsilon", "Υ")
        put("\\Phi", "Φ"); put("\\Chi", "Χ"); put("\\Psi", "Ψ"); put("\\Omega", "Ω")
        // Operators
        put("\\sum", "Σ"); put("\\prod", "∏"); put("\\coprod", "∐")
        put("\\int", "∫"); put("\\iint", "∬"); put("\\iiint", "∭"); put("\\oint", "∮")
        put("\\bigcup", "⋃"); put("\\bigcap", "⋂"); put("\\bigoplus", "⨁")
        put("\\bigotimes", "⨂"); put("\\bigvee", "⨆"); put("\\bigwedge", "⨀")
        // Relations
        put("\\le", "≤"); put("\\leq", "≤"); put("\\ge", "≥"); put("\\geq", "≥")
        put("\\ne", "≠"); put("\\neq", "≠"); put("\\approx", "≈"); put("\\sim", "∼")
        put("\\simeq", "≃"); put("\\cong", "≅"); put("\\equiv", "≡"); put("\\propto", "∝")
        put("\\ll", "≪"); put("\\gg", "≫"); put("\\subset", "⊂"); put("\\supset", "⊃")
        put("\\subseteq", "⊆"); put("\\supseteq", "⊇"); put("\\in", "∈"); put("\\notin", "∉")
        put("\\ni", "∋"); put("\\perp", "⊥"); put("\\parallel", "∥")
        put("\\to", "→"); put("\\rightarrow", "→"); put("\\leftarrow", "←")
        put("\\Rightarrow", "⇒"); put("\\Leftarrow", "⇐")
        put("\\leftrightarrow", "↔"); put("\\Leftrightarrow", "⇔")
        put("\\mapsto", "↦"); put("\\implies", "⟹"); put("\\iff", "⟺")
        // Binary operators
        put("\\pm", "±"); put("\\mp", "∓"); put("\\times", "×"); put("\\div", "÷")
        put("\\cdot", "·"); put("\\ast", "∗"); put("\\star", "⋆")
        put("\\circ", "∘"); put("\\bullet", "•"); put("\\oplus", "⊕")
        put("\\ominus", "⊖"); put("\\otimes", "⊗"); put("\\odot", "⊙")
        put("\\cup", "∪"); put("\\cap", "∩"); put("\\setminus", "∖")
        put("\\vee", "∨"); put("\\wedge", "∧"); put("\\neg", "¬")
        // Misc symbols
        put("\\infty", "∞"); put("\\partial", "∂"); put("\\nabla", "∇")
        put("\\forall", "∀"); put("\\exists", "∃"); put("\\nexists", "∄")
        put("\\emptyset", "∅"); put("\\varnothing", "∅")
        put("\\angle", "∠"); put("\\measuredangle", "∡"); put("\\triangle", "△")
        put("\\degree", "°")
        // Blackboard bold letters
        put("\\mathbb{R}", "ℝ"); put("\\mathbb{N}", "ℕ"); put("\\mathbb{Z}", "ℤ")
        put("\\mathbb{Q}", "ℚ"); put("\\mathbb{C}", "ℂ"); put("\\mathbb{P}", "ℙ")
        // Calligraphic
        put("\\mathcal{L}", "𝓛"); put("\\mathcal{F}", "𝓕")
        // Spacing
        put("\\,", "\u2009"); put("\\;", "\u2003"); put("\\:", "\u2002")
        put("\\!", ""); put("\\quad", "  "); put("\\qquad", "    ")
        // Dots
        put("\\dots", "…"); put("\\ldots", "…"); put("\\cdots", "⋯")
        // Trig
        put("\\sin", "sin"); put("\\cos", "cos"); put("\\tan", "tan")
        put("\\log", "log"); put("\\ln", "ln"); put("\\exp", "exp")
        put("\\lim", "lim"); put("\\max", "max"); put("\\min", "min")
        put("\\sup", "sup"); put("\\inf", "inf"); put("\\det", "det")
        // Roots
        put("\\sqrt", "√")
        // Arrows above
        put("\\hat{a}", "â")
    }

    private fun replaceCommands(s: String): String {
        var out = s
        // Special form first: \hat{X} → X̂
        out = Regex("""\\hat\{([A-Za-z])\}""").replace(out) { m -> m.groupValues[1] + "\u0302" }
        out = Regex("""\\bar\{([A-Za-z])\}""").replace(out) { m -> m.groupValues[1] + "\u0304" }
        out = Regex("""\\vec\{([A-Za-z])\}""").replace(out) { m -> m.groupValues[1] + "\u20D7" }
        out = Regex("""\\tilde\{([A-Za-z])\}""").replace(out) { m -> m.groupValues[1] + "\u0303" }
        // Longest-first ordering matters (\varepsilon before \epsilon).
        val keys = COMMAND_MAP.keys.sortedByDescending { it.length }
        for (k in keys) {
            out = out.replace(k, COMMAND_MAP.getValue(k))
        }
        return out
    }

    // ── Char tables for sup / sub ────────────────────────────────

    private val SUPERSCRIPTS = mapOf(
        "0" to "\u2070", "1" to "\u00B9", "2" to "\u00B2", "3" to "\u00B3",
        "4" to "\u2074", "5" to "\u2075", "6" to "\u2076", "7" to "\u2077",
        "8" to "\u2078", "9" to "\u2079",
        "+" to "\u207A", "-" to "\u207B", "=" to "\u207C", "(" to "\u207D",
        ")" to "\u207E",
        "a" to "\u1D43", "b" to "\u1D47", "c" to "\u1D9C", "d" to "\u1D48",
        "e" to "\u1D49", "f" to "\u1DA0", "g" to "\u1D4D", "h" to "\u02B0",
        "i" to "\u2071", "j" to "\u02B2", "k" to "\u1D4F", "l" to "\u02E1",
        "m" to "\u1D50", "n" to "\u207F", "o" to "\u1D52", "p" to "\u1D56",
        "r" to "\u02B3", "s" to "\u02E2", "t" to "\u1D57", "u" to "\u1D58",
        "v" to "\u1D5B", "w" to "\u02B7", "x" to "\u02E3", "y" to "\u02B8",
        "z" to "\u1DBB",
    )

    private val SUBSCRIPTS = mapOf(
        "0" to "\u2080", "1" to "\u2081", "2" to "\u2082", "3" to "\u2083",
        "4" to "\u2084", "5" to "\u2085", "6" to "\u2086", "7" to "\u2087",
        "8" to "\u2088", "9" to "\u2089",
        "+" to "\u208A", "-" to "\u208B", "=" to "\u208C", "(" to "\u208D",
        ")" to "\u208E",
        "a" to "\u2090", "e" to "\u2091", "h" to "\u2095", "i" to "\u1D62",
        "j" to "\u2C7C", "k" to "\u2096", "l" to "\u2097", "m" to "\u2098",
        "n" to "\u2099", "o" to "\u2092", "p" to "\u209A", "r" to "\u1D63",
        "s" to "\u209B", "t" to "\u209C", "u" to "\u1D64", "v" to "\u1D65",
        "x" to "\u2093",
    )

    private fun charToSuperscript(c: String): String? = SUPERSCRIPTS[c]
    private fun charToSubscript(c: String): String? = SUBSCRIPTS[c]
}

package com.hermexapp.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for G-lite math rendering. The point isn't to cover every LaTeX
 * command — it's to lock in the high-value cases the assistant actually
 * emits (LLMs overwhelmingly use a small vocabulary of math) and to
 * catch regressions in the basic pipeline.
 */
class MathLiteTest {

    // ── findRegions ──────────────────────────────────────────────

    @Test
    fun `inline math is detected`() {
        val r = MathLite.findRegions("Pythagoras: \$a^2 + b^2 = c^2\$")
        assertEquals(1, r.size)
        assertFalse(r[0].isDisplay)
        assertTrue(r[0].annotated.contains("a"))
        assertTrue(r[0].annotated.contains("²"))
    }

    @Test
    fun `display math is detected`() {
        val r = MathLite.findRegions("Here:\n\n\$\$E = mc^2\$\$\n\nDone.")
        assertEquals(1, r.size)
        assertTrue(r[0].isDisplay)
    }

    @Test
    fun `currency is NOT matched as math`() {
        val r = MathLite.findRegions("I paid \$5 and got \$3 back.")
        assertEquals(0, r.size)
    }

    @Test
    fun `display math shadows inline at same position`() {
        val r = MathLite.findRegions("\$\$x = 1\$\$")
        assertEquals(1, r.size)
        assertTrue(r[0].isDisplay)
    }

    @Test
    fun `multiple regions are sorted by start`() {
        val r = MathLite.findRegions("First \$a + b\$, then \$c * d\$, finally.")
        assertEquals(2, r.size)
        assertTrue(r[0].start < r[1].start)
    }

    @Test
    fun `unclosed dollar is not matched`() {
        val r = MathLite.findRegions("oops \$abc without close")
        assertEquals(0, r.size)
    }

    // ── renderBody ───────────────────────────────────────────────

    @Test
    fun `greek letters convert to unicode`() {
        // Math mode collapses inter-token whitespace (LaTeX
        // convention): "\alpha + \beta" → "α+β=γ".
        assertEquals("α+β=γ", MathLite.renderBody("\\alpha + \\beta = \\gamma"))
        assertEquals("Δx/Δt", MathLite.renderBody("\\Delta x / \\Delta t"))
    }

    @Test
    fun `operators convert to unicode`() {
        // Math mode collapses inter-token whitespace.
        assertEquals("a≤b≈c", MathLite.renderBody("a \\le b \\approx c"))
        assertEquals("πr²", MathLite.renderBody("\\pi r^2"))
        assertEquals("Σᵢ₌₁ⁿxᵢ", MathLite.renderBody("\\sum_{i=1}^n x_i"))
    }

    @Test
    fun `fractions convert to slash form`() {
        assertEquals("(a+b)/c", MathLite.renderBody("\\frac{a+b}{c}"))
        // Nested
        assertEquals("(1/2)/(3/4)", MathLite.renderBody("\\frac{\\frac{1}{2}}{\\frac{3}{4}}"))
    }

    @Test
    fun `superscript and subscript convert`() {
        assertEquals("x²", MathLite.renderBody("x^2"))
        assertEquals("x²+y²=z²", MathLite.renderBody("x^2 + y^2 = z^2"))
        assertEquals("xₙ", MathLite.renderBody("x_n"))
        assertEquals("xᵢ₊₁", MathLite.renderBody("x_{i+1}"))
    }

    @Test
    fun `integration and summation glyphs`() {
        // \, maps to thin space but math mode collapses it.
        assertEquals("∫₀¹f(x)dx", MathLite.renderBody("\\int_0^1 f(x)\\,dx"))
        assertEquals("∏ᵢ₌₁ⁿaᵢ", MathLite.renderBody("\\prod_{i=1}^n a_i"))
    }

    @Test
    fun `set notation`() {
        assertEquals("x∈ℝ", MathLite.renderBody("x \\in \\mathbb{R}"))
        assertEquals("∀ε>0,∃δ", MathLite.renderBody("\\forall \\varepsilon > 0, \\exists \\delta"))
    }

    @Test
    fun `trig functions stay ascii (no false bold)`() {
        assertEquals("sin(θ)=cos(θ)", MathLite.renderBody("\\sin(\\theta) = \\cos(\\theta)"))
    }

    @Test
    fun `arrows and relations`() {
        assertEquals("n→∞", MathLite.renderBody("n \\to \\infty"))
        assertEquals("A⊂B⊆C", MathLite.renderBody("A \\subset B \\subseteq C"))
    }

    @Test
    fun `accents above letters`() {
        // x̂ combines the letter with the combining hat.
        assertTrue(MathLite.renderBody("\\hat{x}").contains("\u0302"))
        assertTrue(MathLite.renderBody("\\vec{v}").contains("\u20D7"))
    }

    @Test
    fun `whitespace and leftover braces are cleaned`() {
        // Math mode collapses all whitespace.
        assertEquals("a+b", MathLite.renderBody("  a  +  b  "))
        assertEquals("a+b", MathLite.renderBody("{a} + {b}"))
    }

    @Test
    fun `dotted operators and ellipses`() {
        assertEquals("x₁,…,xₙ", MathLite.renderBody("x_1, \\ldots, x_n"))
        assertEquals("x₁+⋯+xₙ", MathLite.renderBody("x_1 + \\cdots + x_n"))
    }

    // ── replaceWithTags ──────────────────────────────────────────

    @Test
    fun `replaceWithTags emits display tag for display math`() {
        val out = MathLite.replaceWithTags("\$\$x^2\$\$")
        assertTrue(out.contains("⟦display:"))
        assertTrue(out.contains("⟧"))
        assertFalse(out.contains("$"))
    }

    @Test
    fun `replaceWithTags emits inline tag for inline math`() {
        val out = MathLite.replaceWithTags("inline \$a + b\$ here")
        assertTrue(out.contains("⟦inline:"))
        assertFalse(out.contains("⟦display:"))
    }

    @Test
    fun `replaceWithTags handles both in one source`() {
        val out = MathLite.replaceWithTags("Inline \$a\$ and display \$\$b\$\$.")
        assertTrue(out.contains("⟦inline:"))
        assertTrue(out.contains("⟦display:"))
    }

    @Test
    fun `replaceWithTags is a no-op for plain text`() {
        val src = "no math here, just $5 currency and words"
        assertEquals(src, MathLite.replaceWithTags(src))
    }

    @Test
    fun `replaceWithTags round-trips a complex expression`() {
        val src = "Newton: \$\$\\vec{F} = m\\vec{a} = m\\frac{d\\vec{v}}{dt}\$\$"
        val out = MathLite.replaceWithTags(src)
        // Should contain the rendered vector F = m × vector a form
        assertTrue("expected to contain rendered math, got: $out",
            out.contains("⟦display:"))
        assertFalse("no raw $$ should remain", out.contains("$$"))
    }

    // ── end-to-end: real LLM-output shapes ──────────────────────

    @Test
    fun `pythagorean theorem renders cleanly`() {
        val src = "The Pythagorean theorem: \$a^2 + b^2 = c^2\$ for any right triangle."
        val out = MathLite.replaceWithTags(src)
        // Strip the tag delimiters to get the visible body
        val visible = out.replace(Regex("""⟦inline:([^⟧]*)⟧"""), "$1")
        assertTrue(visible.contains("a²+b²=c²"))
    }

    @Test
    fun `integral with limits renders cleanly`() {
        val src = "\$\$\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}\$\$"
        val out = MathLite.replaceWithTags(src)
        val visible = out.replace(Regex("""⟦display:([^⟧]*)⟧"""), "$1")
        assertTrue(visible.contains("∫"))
        assertTrue(visible.contains("∞"))
        assertTrue(visible.contains("π"))
    }

    @Test
    fun `quadratic formula renders cleanly`() {
        val src = "\$\$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\$\$"
        val out = MathLite.replaceWithTags(src)
        val visible = out.replace(Regex("""⟦display:([^⟧]*)⟧"""), "$1")
        // ± should survive
        assertTrue(visible.contains("±"))
        // √ should survive (sqrt maps to the radical glyph)
        assertTrue(visible.contains("√"))
        // / for the fraction
        assertTrue(visible.contains("/"))
    }
}

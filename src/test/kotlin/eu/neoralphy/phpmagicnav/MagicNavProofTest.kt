package eu.neoralphy.phpmagicnav

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpEchoStatement
import com.jetbrains.php.lang.psi.elements.UnaryExpression

/**
 * End-to-end proofs for PHP Magic Method Navigation.
 *
 * Every enabled magic method is exercised on REAL PhpStorm PSI:
 *  - core resolution ([MagicSites.sitesFor]) at each site kind: cast, echo, print, interpolation,
 *    concatenation, `\Stringable`-typed value, union type, and dynamic `__invoke`;
 *  - the gutter provider's per-anchor DEDUP;
 *  - the Goto Declaration handler adding the magic target at the caret;
 *  - the real highlighting daemon actually emitting the gutter markers;
 *  - the settings kill-switch / per-method toggles.
 */
class MagicNavProofTest : BasePlatformTestCase() {

    private val fixture = """
        <?php
        // \Stringable is a bundled PhpStorm stub, but the light headless fixture doesn't load those
        // stubs, so the interface must be declared here for the interface-typed case to resolve.
        interface Stringable {
            public function __toString(): string;
        }
        class Money implements Stringable {
            public function __toString(): string { return "money"; }
        }
        class Coin implements Stringable {
            public function __toString(): string { return "coin"; }
        }
        class Adder {
            public function __invoke(int ${'$'}a, int ${'$'}b): int { return ${'$'}a + ${'$'}b; }
        }
        class Plain {}

        ${'$'}m = new Money();
        ${'$'}s = (string)${'$'}m;      // (1) string cast
        echo ${'$'}m;                  // (2) echo argument
        print ${'$'}m;                 // (3) print argument
        ${'$'}i = "amount: ${'$'}m";    // (4) interpolation
        ${'$'}c = "a" . ${'$'}m . "b";  // (5) concatenation
        echo "x" . ${'$'}m;            // (6) concat inside echo — echo must NOT also mark (dedup)

        ${'$'}add = new Adder();
        ${'$'}r = ${'$'}add(1, 2);      // (7) __invoke

        ${'$'}p = new Plain();
        echo (string)${'$'}p;          // (8) non-Stringable object → no target

        function render(\Stringable ${'$'}x): void {
            echo ${'$'}x;              // (9) value typed as the \Stringable interface
        }
        function pick(bool ${'$'}b): void {
            ${'$'}u = ${'$'}b ? new Money() : new Coin();
            echo ${'$'}u;              // (10) union type Money|Coin
        }
    """.trimIndent()

    private val allMethods = setOf(MagicMethod.TO_STRING, MagicMethod.INVOKE)

    private fun configure(): PhpFile =
        myFixture.configureByText("proof.php", fixture) as PhpFile

    /** All resolved magic sites in the file, keyed by the trimmed text of the enclosing statement. */
    private fun sitesByStatement(file: PhpFile): Map<String, List<MagicSite>> {
        val out = LinkedHashMap<String, MutableList<MagicSite>>()
        PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java).forEach { el ->
            for (site in MagicSites.sitesFor(el, allMethods)) {
                val stmt = PsiTreeUtil.getParentOfType(
                    el, com.jetbrains.php.lang.psi.elements.Statement::class.java, false,
                )
                val key = (stmt ?: el).text.trim()
                out.getOrPut(key) { mutableListOf() }.add(site)
            }
        }
        return out
    }

    private fun MagicSite.labels(): Set<String> = targets.map { MagicMethodResolver.label(it) }.toSet()

    // ---- Core resolution at every site kind ----------------------------------------------------

    fun testResolutionAtEverySiteKind() {
        val file = configure()
        val byStmt = sitesByStatement(file)

        fun assertTargets(stmt: String, magic: MagicMethod, vararg expected: String) {
            val sites = byStmt[stmt].orEmpty().filter { it.magic == magic }
            assertTrue("no $magic site for `$stmt` (have ${byStmt[stmt]})", sites.isNotEmpty())
            val all = sites.flatMap { it.labels() }.toSet()
            assertEquals("targets for `$stmt`", expected.toSet(), all)
        }

        assertTargets("\$s = (string)\$m;", MagicMethod.TO_STRING, "\\Money::__toString")
        assertTargets("echo \$m;", MagicMethod.TO_STRING, "\\Money::__toString")
        assertTargets("print \$m;", MagicMethod.TO_STRING, "\\Money::__toString")
        assertTargets("\$i = \"amount: \$m\";", MagicMethod.TO_STRING, "\\Money::__toString")
        assertTargets("\$c = \"a\" . \$m . \"b\";", MagicMethod.TO_STRING, "\\Money::__toString")
        assertTargets("echo \$x;", MagicMethod.TO_STRING, "\\Stringable::__toString")
        assertTargets(
            "echo \$u;", MagicMethod.TO_STRING,
            "\\Money::__toString", "\\Coin::__toString",
        )
        assertTargets("\$r = \$add(1, 2);", MagicMethod.INVOKE, "\\Adder::__invoke")
    }

    fun testNonStringableObjectHasNoTarget() {
        val file = configure()
        val byStmt = sitesByStatement(file)
        // `echo (string)$p;` where Plain has no __toString → no site at all.
        assertNull("Plain object must produce no __toString site", byStmt["echo (string)\$p;"])
    }

    // ---- Dedup: `echo "x" . $m` marks $m ONCE, not once for echo and once for concat ------------

    fun testDedupThroughProvider() {
        val file = configure()
        val provider = MagicNavLineMarkerProvider()
        val elements = PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java).toMutableList()
        val result = mutableListOf<RelatedItemLineMarkerInfo<*>>()
        provider.collectNavigationMarkers(elements, result, false)

        // Group markers by the (line) offset of their anchor; no anchor may be marked twice.
        val anchorOffsets = result.mapNotNull { it.element?.textOffset }
        assertEquals(
            "each operand leaf must be marked at most once",
            anchorOffsets.toSet().size, anchorOffsets.size,
        )
        // And the concat-inside-echo `$m` is marked exactly once (dedup), not twice.
        val concatEcho = PsiTreeUtil.findChildrenOfType(file, PhpEchoStatement::class.java)
            .first { it.text.trim() == "echo \"x\" . \$m;" }
        val within = result.count {
            it.element?.let { e -> PsiTreeUtil.isAncestor(concatEcho, e, false) } == true
        }
        assertEquals("`echo \"x\" . \$m;` must mark \$m exactly once", 1, within)
    }

    // ---- Gutter markers actually emitted by the real daemon -------------------------------------

    fun testGutterMarkersThroughHighlighting() {
        val file = configure()
        myFixture.doHighlighting()
        val markers: List<LineMarkerInfo<*>> =
            DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
        assertFalse("expected gutter markers", markers.isEmpty())

        val cast = PsiTreeUtil.findChildrenOfType(file, UnaryExpression::class.java)
            .first { it.operation?.node?.elementType == PhpTokenTypes.opSTRING_CAST && it.text == "(string)\$m" }
        assertTrue(
            "expected a marker at the (string)\$m cast",
            markers.any { it.element?.let { e -> PsiTreeUtil.isAncestor(cast, e, false) } == true },
        )
    }

    // ---- Goto Declaration handler adds the magic target at the caret ---------------------------

    fun testGotoDeclarationOffersToString() {
        configure()
        val handler = MagicNavGotoDeclarationHandler()
        // Put the caret on the `$m` inside `echo $m;`.
        val offset = fixture.indexOf("echo \$m;") + "echo ".length + 1 // inside `$m`
        val leaf = myFixture.file.findElementAt(offset)!!
        val targets = handler.getGotoDeclarationTargets(leaf, offset, myFixture.editor)
        assertNotNull("goto should offer a magic target on the operand", targets)
        val labels = targets!!.filterIsInstance<com.jetbrains.php.lang.psi.elements.Method>()
            .map { MagicMethodResolver.label(it) }
        assertTrue("expected Money::__toString among goto targets, got $labels",
            labels.contains("\\Money::__toString"))
    }

    fun testGotoDeclarationOffersInvoke() {
        configure()
        val handler = MagicNavGotoDeclarationHandler()
        val offset = fixture.indexOf("\$add(1, 2)") + 1 // inside `$add`
        val leaf = myFixture.file.findElementAt(offset)!!
        val targets = handler.getGotoDeclarationTargets(leaf, offset, myFixture.editor)!!
        val labels = targets.filterIsInstance<com.jetbrains.php.lang.psi.elements.Method>()
            .map { MagicMethodResolver.label(it) }
        assertTrue("expected Adder::__invoke, got $labels", labels.contains("\\Adder::__invoke"))
    }

    // ---- Settings gating -----------------------------------------------------------------------

    fun testSettingsToggleGatesInvoke() {
        val file = configure()
        // Only __toString enabled → the $add(...) invoke is NOT a site.
        val toStringOnly = setOf(MagicMethod.TO_STRING)
        val invokeSites = PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java)
            .flatMap { MagicSites.sitesFor(it, toStringOnly) }
            .filter { it.magic == MagicMethod.INVOKE }
        assertTrue("__invoke must be suppressed when its toggle is off", invokeSites.isEmpty())

        // Empty enabled set (master switch off) → nothing at all.
        val nothing = PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java)
            .flatMap { MagicSites.sitesFor(it, emptySet()) }
        assertTrue("master switch off must suppress everything", nothing.isEmpty())
    }
}

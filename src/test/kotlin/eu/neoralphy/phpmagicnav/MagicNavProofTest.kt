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

    /**
     * Member-access magic. `Magic` declares real, *public* members (unambiguously accessible, so the
     * negative cases can't be confused with a visibility-triggered magic call) alongside all four
     * member magic methods; `NoMagic` declares none, to prove an undeclared access with no magic
     * method is not a site.
     */
    private val memberFixture = """
        <?php
        class Magic {
            public int ${'$'}realProp = 1;
            public function realMethod(): int { return 1; }
            public static function realStatic(): int { return 1; }
            public function __get(${'$'}name) { return 1; }
            public function __set(${'$'}name, ${'$'}value) {}
            public function __call(${'$'}name, ${'$'}args) { return 1; }
            public static function __callStatic(${'$'}name, ${'$'}args) { return 1; }
        }
        class NoMagic {
            public int ${'$'}realProp = 1;
            public function realMethod(): int { return 1; }
        }

        ${'$'}o = new Magic();
        ${'$'}a = ${'$'}o->missing;          // (1) __get: undeclared property read
        ${'$'}o->missing = 5;               // (2) __set: undeclared property write
        ${'$'}b = ${'$'}o->realProp;         // (3) real property read → NO magic
        ${'$'}o->realProp = 9;              // (4) real property write → NO magic
        ${'$'}r = ${'$'}o->doThing();        // (5) __call: undeclared method
        ${'$'}s = ${'$'}o->realMethod();     // (6) real method → NO magic
        ${'$'}t = Magic::doStatic();        // (7) __callStatic: undeclared static method
        ${'$'}u = Magic::realStatic();      // (8) real static method → NO magic

        ${'$'}n = new NoMagic();
        ${'$'}x = ${'$'}n->undeclared;       // (9) undeclared but no __get on class → NO site
        ${'$'}y = ${'$'}n->realProp;         // (10) real property → NO site
    """.trimIndent()

    private val allMethods = MagicMethod.entries.toSet()

    private fun configure(): PhpFile =
        myFixture.configureByText("proof.php", fixture) as PhpFile

    private fun configureMember(): PhpFile =
        myFixture.configureByText("member.php", memberFixture) as PhpFile

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

    // ---- Member-access magic: __get / __set / __call / __callStatic ----------------------------

    fun testMemberMagicPositiveAndNegative() {
        val file = configureMember()
        val byStmt = sitesByStatement(file)

        fun assertTarget(stmt: String, magic: MagicMethod, expected: String) {
            val sites = byStmt[stmt].orEmpty().filter { it.magic == magic }
            assertTrue("expected a $magic site for `$stmt` (have ${byStmt[stmt]})", sites.isNotEmpty())
            assertEquals(
                "targets for `$stmt`",
                setOf(expected), sites.flatMap { it.labels() }.toSet(),
            )
        }

        fun assertNoSite(stmt: String) {
            assertTrue(
                "`$stmt` must produce no magic site, had ${byStmt[stmt]}",
                byStmt[stmt].isNullOrEmpty(),
            )
        }

        // Positive: the magic method genuinely fires (member does not resolve).
        assertTarget("\$a = \$o->missing;", MagicMethod.GET, "\\Magic::__get")
        assertTarget("\$o->missing = 5;", MagicMethod.SET, "\\Magic::__set")
        assertTarget("\$r = \$o->doThing();", MagicMethod.CALL, "\\Magic::__call")
        assertTarget("\$t = Magic::doStatic();", MagicMethod.CALL_STATIC, "\\Magic::__callStatic")

        // Negative: a real, accessible declared member resolves → the magic method never fires.
        assertNoSite("\$b = \$o->realProp;")
        assertNoSite("\$o->realProp = 9;")
        assertNoSite("\$s = \$o->realMethod();")
        assertNoSite("\$u = Magic::realStatic();")

        // Negative: undeclared access on a class that declares no matching magic method → no site.
        assertNoSite("\$x = \$n->undeclared;")
        assertNoSite("\$y = \$n->realProp;")
    }

    fun testMemberMagicSettingsGating() {
        val file = configureMember()
        // Only __get enabled → the write/call/callStatic member sites are suppressed.
        val getOnly = setOf(MagicMethod.GET)
        val magics = PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java)
            .flatMap { MagicSites.sitesFor(it, getOnly) }
            .map { it.magic }
            .toSet()
        assertEquals("only __get sites when only __get is enabled", setOf(MagicMethod.GET), magics)
    }

    fun testGotoDeclarationOffersGet() {
        configureMember()
        val handler = MagicNavGotoDeclarationHandler()
        val offset = memberFixture.indexOf("\$o->missing;") + "\$o->".length + 1 // inside `missing`
        val leaf = myFixture.file.findElementAt(offset)!!
        val targets = handler.getGotoDeclarationTargets(leaf, offset, myFixture.editor)
        assertNotNull("goto should offer __get on an undeclared property read", targets)
        val labels = targets!!.filterIsInstance<com.jetbrains.php.lang.psi.elements.Method>()
            .map { MagicMethodResolver.label(it) }
        assertTrue("expected Magic::__get among goto targets, got $labels",
            labels.contains("\\Magic::__get"))
    }

    // ---- Reverse Find Usages: implicit sites surface from the magic declaration -----------------

    /** Run the custom usage searcher for [magicMethodName] on class [className] and return the
     *  text of every implicit-usage element it reports. */
    private fun reverseUsageTexts(className: String, magicMethodName: String): List<String> {
        val method = PsiTreeUtil
            .findChildrenOfType(myFixture.file, com.jetbrains.php.lang.psi.elements.Method::class.java)
            .first { it.name == magicMethodName && it.containingClass?.name == className }
        val usages = mutableListOf<com.intellij.usages.Usage>()
        val processor = com.intellij.util.Processor<com.intellij.usages.Usage> { usages.add(it); true }
        val options = com.intellij.find.findUsages.FindUsagesOptions(
            com.intellij.psi.search.GlobalSearchScope.allScope(project),
        )
        MagicNavUsageSearcher().processElementUsages(method, processor, options)
        return usages.mapNotNull { (it as? com.intellij.usages.UsageInfo2UsageAdapter)?.element?.text }
    }

    fun testReverseFindUsagesToString() {
        configure()
        val texts = reverseUsageTexts("Money", "__toString")
        // Every implicit __toString site that dispatches to Money::__toString must be surfaced.
        assertTrue("reverse usages should include the (string) cast operand, got $texts",
            texts.any { it.contains("\$m") })
        // The Coin/Stringable/Adder sites must NOT be attributed to Money::__toString.
        assertTrue("reverse usages must not surface the __invoke callee, got $texts",
            texts.none { it.contains("\$add") })
        assertFalse("expected at least one reverse usage for Money::__toString", texts.isEmpty())
    }

    fun testReverseFindUsagesGet() {
        configureMember()
        val texts = reverseUsageTexts("Magic", "__get")
        assertTrue("reverse usages of __get should surface the \$o->missing read, got $texts",
            texts.any { it.contains("missing") })
        // The write site is __set, not __get → must not appear here.
        assertTrue("the property write must not be attributed to __get, got $texts",
            texts.none { it.contains("doThing") })
    }
}

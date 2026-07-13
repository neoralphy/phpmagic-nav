package eu.neoralphy.phpmagicnav

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Statement

/**
 * Adversarial edge-case coverage for PHP Magic Method Navigation.
 *
 * These probe the tricky PHP shapes the main proof test does not: inheritance / traits / transitive
 * interfaces, static-vs-instance dispatch, lowercase-named classes, `.=`, nested casts, heredoc /
 * nowdoc, complex interpolation, first-class-callable syntax, phpdoc members, incomplete PSI, and
 * empty reverse-usage results. Two of them (lowercase class, `.=`) are regressions for confirmed
 * false-negative bugs fixed on this branch.
 */
class MagicNavEdgeCaseTest : BasePlatformTestCase() {

    private val all = MagicMethod.entries.toSet()

    private fun sites(code: String): Map<String, List<MagicSite>> {
        val file = myFixture.configureByText("edge.php", code) as PhpFile
        return sitesByStatement(file)
    }

    private fun sitesByStatement(file: PhpFile): Map<String, List<MagicSite>> {
        val out = LinkedHashMap<String, MutableList<MagicSite>>()
        PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java).forEach { el ->
            for (site in MagicSites.sitesFor(el, all)) {
                val stmt = PsiTreeUtil.getParentOfType(el, Statement::class.java, false)
                val key = (stmt ?: el).text.trim()
                out.getOrPut(key) { mutableListOf() }.add(site)
            }
        }
        return out
    }

    private fun MagicSite.labels(): Set<String> =
        targets.map { MagicMethodResolver.label(it) }.toSet()

    private fun Map<String, List<MagicSite>>.magicFor(stmt: String, magic: MagicMethod): List<MagicSite> =
        this[stmt].orEmpty().filter { it.magic == magic }

    private fun assertNoSite(byStmt: Map<String, List<MagicSite>>, stmt: String) =
        assertTrue("`$stmt` must produce no magic site, had ${byStmt[stmt]}", byStmt[stmt].isNullOrEmpty())

    // ---- Fix regression: lowercase-named classes are real classes, not primitives ---------------

    fun testLowercaseClassNameIsDetected() {
        val byStmt = sites(
            """
            <?php
            class money { public function __toString(): string { return "m"; } }
            class adder { public function __invoke(): int { return 1; } }
            class bag {
                public function __get(${'$'}n) { return 1; }
                public function __call(${'$'}n, ${'$'}a) { return 1; }
            }
            ${'$'}m = new money();
            echo ${'$'}m;                 // lowercase class in string context
            ${'$'}c = (string)${'$'}m;
            ${'$'}a = new adder();
            ${'$'}r = ${'$'}a();            // lowercase class __invoke
            ${'$'}b = new bag();
            ${'$'}g = ${'$'}b->missing;     // lowercase class __get
            ${'$'}k = ${'$'}b->run();       // lowercase class __call
            """.trimIndent(),
        )
        assertEquals(setOf("\\money::__toString"), byStmt.magicFor("echo \$m;", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet())
        assertEquals(setOf("\\money::__toString"), byStmt.magicFor("\$c = (string)\$m;", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet())
        assertEquals(setOf("\\adder::__invoke"), byStmt.magicFor("\$r = \$a();", MagicMethod.INVOKE).flatMap { it.labels() }.toSet())
        assertEquals(setOf("\\bag::__get"), byStmt.magicFor("\$g = \$b->missing;", MagicMethod.GET).flatMap { it.labels() }.toSet())
        assertEquals(setOf("\\bag::__call"), byStmt.magicFor("\$k = \$b->run();", MagicMethod.CALL).flatMap { it.labels() }.toSet())
    }

    fun testPrimitivesStillProduceNoSite() {
        // Guard the fix didn't over-reach: genuine scalars/arrays must remain unmarked.
        val byStmt = sites(
            """
            <?php
            ${'$'}s = "hi";
            ${'$'}i = 5;
            ${'$'}arr = [1, 2];
            echo ${'$'}s;
            echo (string)${'$'}i;
            ${'$'}x = "a" . ${'$'}s;
            """.trimIndent(),
        )
        assertNoSite(byStmt, "echo \$s;")
        assertNoSite(byStmt, "echo (string)\$i;")
        assertNoSite(byStmt, "\$x = \"a\" . \$s;")
    }

    // ---- Fix regression: `.=` compound concat coerces its RHS to string -------------------------

    fun testConcatAssignMarksRhs() {
        val byStmt = sites(
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            ${'$'}m = new Money();
            ${'$'}s = "";
            ${'$'}s .= ${'$'}m;            // RHS coerced via __toString
            ${'$'}n = 0;
            ${'$'}n += 5;                 // NOT a string context — must stay unmarked
            """.trimIndent(),
        )
        assertEquals(
            "`.=` RHS must resolve to Money::__toString",
            setOf("\\Money::__toString"),
            byStmt.magicFor("\$s .= \$m;", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet(),
        )
        assertNoSite(byStmt, "\$n += 5;")
    }

    fun testConcatAssignWithNestedConcatSelfDedups() {
        // `$s .= $a . $m`: the RHS is itself a concat (type string), so the assignment node
        // contributes nothing and only the inner concat's operands mark — no double count.
        val file = myFixture.configureByText(
            "edge.php",
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            ${'$'}m = new Money();
            ${'$'}a = new Money();
            ${'$'}s = "";
            ${'$'}s .= ${'$'}a . ${'$'}m;
            """.trimIndent(),
        ) as PhpFile
        val provider = MagicNavLineMarkerProvider()
        val elements = PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java).toMutableList()
        val result = mutableListOf<com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<*>>()
        provider.collectNavigationMarkers(elements, result, false)
        val offsets = result.mapNotNull { it.element?.textOffset }
        assertEquals("no leaf may be marked twice", offsets.toSet().size, offsets.size)
        // Exactly two operands ($a and $m), each once.
        assertEquals("expected exactly two marks for \$a and \$m", 2, offsets.size)
    }

    // ---- Inheritance / traits / transitive interfaces -------------------------------------------

    fun testToStringInheritedFromParentClass() {
        val byStmt = sites(
            """
            <?php
            class Base { public function __toString(): string { return "b"; } }
            class Derived extends Base {}
            ${'$'}d = new Derived();
            echo ${'$'}d;
            """.trimIndent(),
        )
        val labels = byStmt.magicFor("echo \$d;", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet()
        assertEquals("subclass instance dispatches to the parent's __toString", setOf("\\Base::__toString"), labels)
    }

    fun testToStringFromTrait() {
        val byStmt = sites(
            """
            <?php
            trait StringableTrait { public function __toString(): string { return "t"; } }
            class Widget { use StringableTrait; }
            ${'$'}w = new Widget();
            echo ${'$'}w;
            """.trimIndent(),
        )
        val sites = byStmt.magicFor("echo \$w;", MagicMethod.TO_STRING)
        assertTrue("a trait-provided __toString must be a site", sites.isNotEmpty())
        assertTrue("target method is __toString", sites.flatMap { it.targets }.all { it.name == "__toString" })
    }

    fun testGetInheritedFromParentUsedOnSubclass() {
        val byStmt = sites(
            """
            <?php
            class Base { public function __get(${'$'}n) { return 1; } }
            class Derived extends Base {}
            ${'$'}d = new Derived();
            ${'$'}v = ${'$'}d->missing;
            """.trimIndent(),
        )
        val labels = byStmt.magicFor("\$v = \$d->missing;", MagicMethod.GET).flatMap { it.labels() }.toSet()
        assertEquals("undeclared read on subclass hits parent __get", setOf("\\Base::__get"), labels)
    }

    fun testStringableTransitiveInterface() {
        val byStmt = sites(
            """
            <?php
            interface Stringable { public function __toString(): string; }
            interface Nameable extends Stringable {}
            class Widget implements Nameable { public function __toString(): string { return "w"; } }
            function r(Nameable ${'$'}n): void { echo ${'$'}n; }
            """.trimIndent(),
        )
        val sites = byStmt.magicFor("echo \$n;", MagicMethod.TO_STRING)
        assertTrue("value typed as a transitive Stringable sub-interface is a site", sites.isNotEmpty())
        assertTrue("target is a __toString declaration", sites.flatMap { it.targets }.all { it.name == "__toString" })
    }

    // ---- Static vs instance: no cross-wiring between __call and __callStatic ---------------------

    fun testCallVsCallStaticNoCrossWire() {
        val byStmt = sites(
            """
            <?php
            class OnlyCall { public function __call(${'$'}n, ${'$'}a) { return 1; } }
            class OnlyStatic { public static function __callStatic(${'$'}n, ${'$'}a) { return 1; } }
            ${'$'}oc = new OnlyCall();
            ${'$'}a = ${'$'}oc->go();          // instance + __call → CALL
            ${'$'}b = OnlyCall::go();          // static + only __call → NO site (would fatal in PHP)
            ${'$'}c = OnlyStatic::go();        // static + __callStatic → CALL_STATIC
            ${'$'}os = new OnlyStatic();
            ${'$'}d = ${'$'}os->go();          // instance + only __callStatic → NO site
            """.trimIndent(),
        )
        assertEquals(setOf("\\OnlyCall::__call"), byStmt.magicFor("\$a = \$oc->go();", MagicMethod.CALL).flatMap { it.labels() }.toSet())
        assertEquals(setOf("\\OnlyStatic::__callStatic"), byStmt.magicFor("\$c = OnlyStatic::go();", MagicMethod.CALL_STATIC).flatMap { it.labels() }.toSet())
        assertNoSite(byStmt, "\$b = OnlyCall::go();")
        assertNoSite(byStmt, "\$d = \$os->go();")
    }

    // ---- Types: union with a single magic member, mixed / untyped -------------------------------

    fun testUnionWithOnlyOneMagicMember() {
        val byStmt = sites(
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            class Plain {}
            function pick(bool ${'$'}b): void {
                ${'$'}u = ${'$'}b ? new Money() : new Plain();
                echo ${'$'}u;
            }
            """.trimIndent(),
        )
        val labels = byStmt.magicFor("echo \$u;", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet()
        assertEquals("only the union member that declares __toString is offered", setOf("\\Money::__toString"), labels)
    }

    fun testUntypedOperandNoSiteNoCrash() {
        val byStmt = sites(
            """
            <?php
            function f(${'$'}x): void { echo ${'$'}x; }        // no inferable type
            function g(mixed ${'$'}y): void { echo ${'$'}y; }  // explicit mixed
            """.trimIndent(),
        )
        assertNoSite(byStmt, "echo \$x;")
        assertNoSite(byStmt, "echo \$y;")
    }

    // ---- String contexts: nested cast, heredoc / nowdoc, complex interpolation ------------------

    fun testNestedCastMarksOnce() {
        val file = myFixture.configureByText(
            "edge.php",
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            ${'$'}m = new Money();
            ${'$'}s = (string)(string)${'$'}m;
            """.trimIndent(),
        ) as PhpFile
        val provider = MagicNavLineMarkerProvider()
        val elements = PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java).toMutableList()
        val result = mutableListOf<com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<*>>()
        provider.collectNavigationMarkers(elements, result, false)
        assertEquals("nested (string)(string)\$m marks \$m exactly once", 1, result.size)
    }

    fun testHeredocInterpolatesNowdocDoesNot() {
        val byStmt = sites(
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            ${'$'}m = new Money();
            ${'$'}h = <<<EOT
            amount ${'$'}m here
            EOT;
            ${'$'}n = <<<'EOT'
            amount ${'$'}m here
            EOT;
            """.trimIndent(),
        )
        // Heredoc interpolates → the $m embedded in it is a __toString site.
        val heredocSites = byStmt.entries.filter { it.key.contains("<<<EOT") }.flatMap { it.value }
            .filter { it.magic == MagicMethod.TO_STRING }
        assertTrue("heredoc interpolation must mark \$m", heredocSites.isNotEmpty())
        // Nowdoc is literal → no embedded expression, no site.
        val nowdocSites = byStmt.entries.filter { it.key.contains("<<<'EOT'") }.flatMap { it.value }
        assertTrue("nowdoc must not mark anything, had $nowdocSites", nowdocSites.isEmpty())
    }

    fun testComplexInterpolationUsesFieldType() {
        // `"$u->money"` / `"{$u->money}"`: the operand is the PROPERTY, so the property's type
        // (Money) drives __toString — not the receiver $u's type.
        val byStmt = sites(
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            class Wallet {
                public Money ${'$'}money;
                public string ${'$'}label = "x";
            }
            ${'$'}w = new Wallet();
            ${'$'}a = "simple ${'$'}w->money";
            ${'$'}b = "brace {${'$'}w->money}";
            ${'$'}c = "plain ${'$'}w->label";
            """.trimIndent(),
        )
        val simple = byStmt.magicFor("\$a = \"simple \$w->money\";", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet()
        val brace = byStmt.magicFor("\$b = \"brace {\$w->money}\";", MagicMethod.TO_STRING).flatMap { it.labels() }.toSet()
        assertEquals("simple property interpolation resolves the field type", setOf("\\Money::__toString"), simple)
        assertEquals("brace property interpolation resolves the field type", setOf("\\Money::__toString"), brace)
        // A string-typed property must NOT be marked.
        assertNoSite(byStmt, "\$c = \"plain \$w->label\";")
    }

    // ---- First-class callable syntax vs an actual invoke ----------------------------------------

    fun testFirstClassCallableIsNotAnInvoke() {
        val byStmt = sites(
            """
            <?php
            class Adder { public function __invoke(): int { return 1; } }
            ${'$'}a = new Adder();
            ${'$'}fcc = ${'$'}a(...);   // first-class callable: makes a Closure, does NOT invoke
            ${'$'}res = ${'$'}a();      // real invoke
            """.trimIndent(),
        )
        assertNoSite(byStmt, "\$fcc = \$a(...);")
        assertEquals(
            "an actual call still resolves __invoke",
            setOf("\\Adder::__invoke"),
            byStmt.magicFor("\$res = \$a();", MagicMethod.INVOKE).flatMap { it.labels() }.toSet(),
        )
    }

    // ---- False-positive guards: phpdoc members, real members ------------------------------------

    fun testPhpdocPropertyAndMethodNotMarked() {
        val byStmt = sites(
            """
            <?php
            /**
             * @property string ${'$'}docProp
             * @method int docMethod()
             */
            class Documented {
                public function __get(${'$'}n) { return 1; }
                public function __call(${'$'}n, ${'$'}a) { return 1; }
            }
            ${'$'}d = new Documented();
            ${'$'}a = ${'$'}d->docProp;      // documented via @property → IDE navigates natively
            ${'$'}b = ${'$'}d->docMethod();  // documented via @method → IDE navigates natively
            ${'$'}c = ${'$'}d->undocumented; // genuinely undeclared → __get fires
            """.trimIndent(),
        )
        assertNoSite(byStmt, "\$a = \$d->docProp;")
        assertNoSite(byStmt, "\$b = \$d->docMethod();")
        assertEquals(
            "a genuinely undeclared read still fires __get",
            setOf("\\Documented::__get"),
            byStmt.magicFor("\$c = \$d->undocumented;", MagicMethod.GET).flatMap { it.labels() }.toSet(),
        )
    }

    fun testPrivateMemberFromInsideClassResolves() {
        // A private member accessed from INSIDE the class resolves to its real declaration, so no
        // magic marker — even though the class also declares __get/__call. (The documented
        // visibility trade-off only concerns access from OUTSIDE the class.)
        val byStmt = sites(
            """
            <?php
            class Safe {
                private int ${'$'}secret = 1;
                private function hidden(): int { return 1; }
                public function __get(${'$'}n) { return 1; }
                public function __call(${'$'}n, ${'$'}a) { return 1; }
                public function reach(): int {
                    ${'$'}x = ${'$'}this->secret;
                    ${'$'}y = ${'$'}this->hidden();
                    return ${'$'}x + ${'$'}y;
                }
            }
            """.trimIndent(),
        )
        assertNoSite(byStmt, "\$x = \$this->secret;")
        assertNoSite(byStmt, "\$y = \$this->hidden();")
    }

    // ---- Reverse Find Usages: empty result, no crash --------------------------------------------

    fun testReverseFindUsagesEmptyWhenNoImplicitSites() {
        val file = myFixture.configureByText(
            "edge.php",
            """
            <?php
            class Lonely { public function __toString(): string { return "x"; } }
            ${'$'}l = new Lonely();
            ${'$'}explicit = ${'$'}l->__toString();  // explicit call is NOT an implicit site
            """.trimIndent(),
        ) as PhpFile
        val method = PsiTreeUtil.findChildrenOfType(file, Method::class.java)
            .first { it.name == "__toString" && it.containingClass?.name == "Lonely" }
        val usages = mutableListOf<com.intellij.usages.Usage>()
        val processor = com.intellij.util.Processor<com.intellij.usages.Usage> { usages.add(it); true }
        val options = com.intellij.find.findUsages.FindUsagesOptions(
            com.intellij.psi.search.GlobalSearchScope.allScope(project),
        )
        // Must not throw and must yield nothing (only an explicit call exists).
        MagicNavUsageSearcher().processElementUsages(method, processor, options)
        assertTrue("no implicit site → empty reverse usages, had ${usages.size}", usages.isEmpty())
    }

    // ---- Robustness: incomplete / erroring PSI must not throw -----------------------------------

    fun testIncompletePsiDoesNotThrow() {
        val file = myFixture.configureByText(
            "broken.php",
            """
            <?php
            class Money { public function __toString(): string { return "m"; } }
            ${'$'}m = new Money();
            ${'$'}a = ${'$'}m->;
            echo ${'$'}m .
            ${'$'}b = (string)
            ${'$'}c = ${'$'}m(
            ${'$'}d = ${'$'}undefined->missing;
            ${'$'}e = Nonexistent::gone();
            """.trimIndent(),
        ) as PhpFile
        // The whole point: walking every node with sitesFor must never throw on malformed PSI.
        var count = 0
        for (node in PsiTreeUtil.collectElementsOfType(file, PsiElement::class.java)) {
            count += MagicSites.sitesFor(node, all).size
        }
        assertTrue("sitesFor survived the malformed file (produced $count sites)", count >= 0)
    }
}

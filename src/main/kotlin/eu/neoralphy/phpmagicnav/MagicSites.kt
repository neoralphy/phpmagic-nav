package eu.neoralphy.phpmagicnav

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpEchoStatement
import com.jetbrains.php.lang.psi.elements.PhpPrintExpression
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.UnaryExpression

/**
 * Detects the implicit magic-method invocation sites in a PHP PSI tree and resolves each to its
 * target method(s). One place decides *what is a site*, so the gutter markers and the Goto handler
 * can never disagree.
 *
 * ## Performance contract
 * [sitesFor] runs on **every** PSI element in the daemon's slow pass. The very first thing it does
 * is a cheap `instanceof` gate on the concrete node types below; only for those does it touch
 * [PhpTypedElement.getType]/`global()` (the expensive part). Non-string / non-callable operands fall
 * out fast via the primitive skip in [MagicMethodResolver]. Callers must respect [MagicNavSettings]
 * and pass in which [MagicMethod]s are enabled so disabled work is never resolved at all.
 */
object MagicSites {

    /**
     * Return every magic-method navigation site *rooted at* [element]. Empty unless [element] is one
     * of the recognised container nodes AND at least one operand resolves to an enabled magic method.
     *
     * The container→operand mapping (all confirmed against real PhpStorm PSI):
     *  - `(string)` cast    — [UnaryExpression] with an `opSTRING_CAST` operation; operand = its value
     *  - `echo`             — [PhpEchoStatement]; operands = its arguments
     *  - `print`            — [PhpPrintExpression]; operand = its argument
     *  - interpolation      — [StringLiteralExpression]; operands = its embedded expressions (`"$m"`)
     *  - concatenation      — [BinaryExpression]/[com.jetbrains.php.lang.psi.elements.ConcatenationExpression];
     *                         operands = left & right (chained concat is nested, handled per node)
     *  - dynamic invoke     — [FunctionReference] whose callee is an expression (`$callable(...)`);
     *                         operand = the callee, magic = `__invoke`
     *
     * Operands whose type is a scalar/string resolve to nothing and are silently dropped — which is
     * exactly what makes the nested cases self-dedup (e.g. `echo "x" . $m`: the echo argument is the
     * whole concat expression, type `string`, so echo contributes nothing and only the concat's `$m`
     * operand marks).
     */
    fun sitesFor(element: PsiElement, enabled: Set<MagicMethod>): List<MagicSite> {
        // ---- __invoke: dynamic call of an expression ------------------------------------------
        if (MagicMethod.INVOKE in enabled) {
            invokeCallee(element)?.let { callee ->
                return resolved(listOf(callee), MagicMethod.INVOKE)
            }
        }

        if (MagicMethod.TO_STRING !in enabled) return emptyList()

        // ---- __toString: string-context operands ----------------------------------------------
        val operands: List<PhpTypedElement> = when {
            element is UnaryExpression && isStringCast(element) ->
                listOfNotNull(element.value as? PhpTypedElement)

            element is PhpEchoStatement ->
                element.arguments.mapNotNull { it as? PhpTypedElement }

            element is PhpPrintExpression ->
                listOfNotNull(element.argument as? PhpTypedElement)

            // ConcatenationExpression IS a BinaryExpression; match on the concat operator so we don't
            // treat `$a + $b` as a string context.
            element is BinaryExpression && isConcatenation(element) ->
                listOfNotNull(
                    element.leftOperand as? PhpTypedElement,
                    element.rightOperand as? PhpTypedElement,
                )

            // Only *interpolated* (double-quoted / heredoc) strings have embedded PSI children;
            // a plain literal has none, so this yields an empty list for `'x'` / `"x"`.
            element is StringLiteralExpression ->
                interpolationOperands(element)

            else -> emptyList()
        }

        return resolved(operands, MagicMethod.TO_STRING)
    }

    /**
     * If [element] is a dynamic invoke — a [FunctionReference] whose callee is an *expression*
     * (`$c(...)`, `($this->factory)(...)`, `$arr[0](...)`) rather than a named function — return that
     * callee expression; otherwise null.
     *
     * A named call like `strlen($x)` is also a [FunctionReference] but its callee is an identifier
     * leaf, not a [PhpTypedElement], so it yields null. [MethodReference] (`$o->m()`, `A::m()`) is a
     * separate type and never matches here.
     */
    fun invokeCallee(element: PsiElement): PhpTypedElement? {
        if (element !is FunctionReference || element is MethodReference) return null
        // The callee is the reference's own first expression child. For `$c(...)` that's the
        // Variable `$c`; for a named call it's an identifier leaf (not a PhpTypedElement).
        val callee = element.firstPsiChild as? PhpTypedElement ?: return null
        return callee
    }

    private fun resolved(operands: List<PhpTypedElement>, magic: MagicMethod): List<MagicSite> {
        if (operands.isEmpty()) return emptyList()
        val sites = ArrayList<MagicSite>(operands.size)
        for (operand in operands) {
            val targets = MagicMethodResolver.resolveMagicTargets(operand, magic)
            if (targets.isNotEmpty()) sites.add(MagicSite(operand, magic, targets))
        }
        return sites
    }

    private fun interpolationOperands(str: StringLiteralExpression): List<PhpTypedElement> =
        str.children.mapNotNull { child ->
            when (child) {
                // Simple `"$m"` — the Variable is a direct child (no PhpTypedElement child of its own).
                // Complex `"{$m->prop}"` — the direct child is a brace-wrapper Variable around a
                // FieldReference/MethodReference; unwrap one level to the meaningful expression.
                is PhpTypedElement ->
                    PsiTreeUtil.getChildOfType(child, PhpTypedElement::class.java) ?: child
                else -> null
            }
        }

    private fun isStringCast(unary: UnaryExpression): Boolean =
        unary.operation?.node?.elementType == PhpTokenTypes.opSTRING_CAST

    private fun isConcatenation(binary: BinaryExpression): Boolean =
        binary.operation?.node?.elementType == PhpTokenTypes.opCONCAT
}

package eu.neoralphy.phpmagicnav

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpEchoStatement
import com.jetbrains.php.lang.psi.elements.PhpPrintExpression
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.SelfAssignmentExpression
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
     *  - concat-assign      — [SelfAssignmentExpression] `$s .= $obj` (`.=` only); operand = its RHS value
     *  - dynamic invoke     — [FunctionReference] whose callee is an expression (`$callable(...)`);
     *                         operand = the callee, magic = `__invoke`
     *  - property read      — [FieldReference] `$o->p` that doesn't resolve → `__get`; operand = the ref
     *  - property write     — [FieldReference] `$o->p = …` (write access) that doesn't resolve → `__set`;
     *                         a read-modify-write (`$o->p += …`, `$o->p++`) resolves to BOTH `__get` and `__set`
     *  - method call        — [MethodReference] `$o->m()` / `Foo::m()` that doesn't resolve →
     *                         `__call` / `__callStatic`; operand = the whole call reference
     *
     * Operands whose type is a scalar/string resolve to nothing and are silently dropped — which is
     * exactly what makes the nested cases self-dedup (e.g. `echo "x" . $m`: the echo argument is the
     * whole concat expression, type `string`, so echo contributes nothing and only the concat's `$m`
     * operand marks).
     */
    fun sitesFor(element: PsiElement, enabled: Set<MagicMethod>): List<MagicSite> {
        // ---- member-access magic: __get / __set / __call / __callStatic -----------------------
        // Cheapest gate first (instanceof). These node types are disjoint from every string/invoke
        // container below, so once we know it's a member reference we can return its result directly.
        when (element) {
            is MethodReference -> return methodCallSite(element, enabled)
            is FieldReference -> return fieldAccessSite(element, enabled)
            else -> {}
        }

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

            // `$s .= $obj` desugars to `$s = $s . $obj`, so the right-hand value is coerced to string
            // exactly like a plain concat — the same string context, just an assignment node instead
            // of a BinaryExpression. Only the RHS is a *new* operand entering string context (the LHS
            // is the already-string accumulator). Gate strictly on `.=` so `$n += $x` isn't caught.
            element is SelfAssignmentExpression && isConcatAssign(element) ->
                listOfNotNull(element.value as? PhpTypedElement)

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

    /**
     * `$obj->method(...)` (or `Foo::method(...)`) → `__call` / `__callStatic`, but only when `method`
     * does NOT resolve to a real declared method AND the receiver's class declares the magic method.
     * The receiver expression (`element.classReference`) supplies the type we resolve the magic
     * method on; the whole [MethodReference] is the site's operand (what the gutter/goto attaches to).
     */
    private fun methodCallSite(element: MethodReference, enabled: Set<MagicMethod>): List<MagicSite> {
        val magic = if (element.isStatic) MagicMethod.CALL_STATIC else MagicMethod.CALL
        if (magic !in enabled) return emptyList()
        // Cheap short-circuit on the common path: a call that resolves to a real method is not magic.
        // Doing this *before* the expensive `getType().global()` keeps ordinary calls cheap.
        if (resolvesToRealMember(element)) return emptyList()
        val receiver = element.classReference ?: return emptyList()
        val targets = MagicMethodResolver.resolveMagicTargets(receiver, magic)
        return if (targets.isEmpty()) emptyList() else listOf(MagicSite(element, magic, targets))
    }

    /**
     * `$obj->prop` → `__get` (read) or `__set` (write), but only when `prop` does NOT resolve to a
     * real declared property/const AND the receiver's class declares the magic method. Read vs write
     * comes from the platform's [com.jetbrains.php.lang.psi.elements.RWAccess] on the reference, so a
     * write on the LHS of `=` picks `__set` and everything else picks `__get`.
     *
     * Static field access (`Foo::$prop`) never triggers `__get`/`__set`, so it is excluded up front.
     * Class-constant access (`Foo::BAR`) is a separate [com.jetbrains.php.lang.psi.elements.ClassConstantReference],
     * not a [FieldReference], so it never reaches here. (Note: [FieldReference.isConstant] is *not* a
     * class-constant test — it reports read/rvalue context — so it must not be used to gate this.)
     */
    private fun fieldAccessSite(element: FieldReference, enabled: Set<MagicMethod>): List<MagicSite> {
        if (element.isStatic) return emptyList()
        // Which member-magic methods PHP fires on this access. A plain read fires `__get`; a plain
        // write (`$o->p = …`) fires `__set`; but a *read-modify-write* (`$o->p += …`, `$o->p++`,
        // `--$o->p`) fires BOTH — PHP reads the current value via `__get`, applies the operator, then
        // writes the result via `__set`. The platform's RWAccess only exposes `isWriteAccess()` (no
        // ReadWrite state), so the compound/inc-dec case is recognised from the parent PSI.
        val magics: List<MagicMethod> = when {
            !element.isWriteAccess -> listOf(MagicMethod.GET)
            isReadModifyWrite(element) -> listOf(MagicMethod.GET, MagicMethod.SET)
            else -> listOf(MagicMethod.SET)
        }
        // Cheap gates before the expensive receiver-type resolution: bail if none of the methods PHP
        // would fire here are enabled, or the member resolves to a real declaration.
        if (magics.none { it in enabled }) return emptyList()
        if (resolvesToRealMember(element)) return emptyList()
        val receiver = element.classReference ?: return emptyList()
        val sites = ArrayList<MagicSite>(magics.size)
        for (magic in magics) {
            if (magic !in enabled) continue
            val targets = MagicMethodResolver.resolveMagicTargets(receiver, magic)
            if (targets.isNotEmpty()) sites.add(MagicSite(element, magic, targets))
        }
        return sites
    }

    /**
     * Is this write-access field reference part of a read-modify-write — a compound assignment
     * (`+=`, `.=`, `??=`, …) or an increment/decrement (`$o->p++`, `--$o->p`)? Those read the current
     * value (via `__get`) before writing the new one (via `__set`); a plain `$o->p = …` does not.
     * Only ever called once [FieldReference.isWriteAccess] is already known true.
     */
    private fun isReadModifyWrite(element: FieldReference): Boolean =
        when (val parent = element.parent) {
            // `$o->p += …` etc. — guard that `element` is the assignment *target* (LHS `variable`),
            // not some incidental value expression.
            is SelfAssignmentExpression -> parent.variable === element
            // `$o->p++` / `--$o->p`: the only unary operators that write to a field are `++`/`--`,
            // and we only reach here when isWriteAccess() already confirmed a write.
            is UnaryExpression -> true
            else -> false
        }

    /**
     * Does this member reference point at a *real declared* member — a property/const for a field
     * ref, or the actually-named method for a call? A resolution that lands only on the magic method
     * itself (`__get`/`__call`, whose name differs from the accessed member) does NOT count, so those
     * sites are still recognised.
     *
     * Note (heuristic boundary): PhpStorm resolves *by declaration*, not by call-site accessibility.
     * A `private`/`protected` member reached from an outside scope still resolves here, so a magic
     * call triggered purely by *visibility* (PHP invokes `__get`/`__call` for inaccessible members)
     * is intentionally NOT marked — we prefer missing that rare case to falsely marking legitimate
     * member access. A member documented via `@property`/`@method` phpdoc also resolves (to the
     * phpdoc declaration) and is treated as real, since the IDE already navigates it natively.
     */
    private fun resolvesToRealMember(ref: PhpReference): Boolean {
        val name = ref.name ?: return false
        for (result in ref.multiResolve(false)) {
            when (val target = result.element) {
                // Any real property/const declaration the field ref lands on.
                is Field -> return true
                // A method with the *same* name as the call is the real target; a different-named
                // method (i.e. `__call`/`__callStatic`) is the magic dispatcher, so it doesn't count.
                is Method -> if (target.name.equals(name, ignoreCase = true)) return true
            }
        }
        return false
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

    private fun isConcatAssign(assign: SelfAssignmentExpression): Boolean =
        assign.operationType == PhpTokenTypes.opCONCAT_ASGN
}

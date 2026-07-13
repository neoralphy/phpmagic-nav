package eu.neoralphy.phpmagicnav

import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpTypedElement

/**
 * The PHP "magic methods" this plugin can navigate to from their *implicit* invocation sites.
 *
 * PhpStorm resolves an explicit `$x->__toString()` call natively, but the language invokes these
 * methods implicitly — a `(string)` cast never mentions `__toString`, `$callable(...)` never
 * mentions `__invoke` — and the IDE offers no jump from those sites to the method that actually
 * runs. That gap is what this plugin fills.
 */
enum class MagicMethod(
    /** The PHP method name to resolve on the operand's type. */
    val methodName: String,
    /** Human label used in gutter tooltips and the Goto Declaration popup. */
    val display: String,
) {
    TO_STRING("__toString", "__toString()"),
    INVOKE("__invoke", "__invoke()"),
}

/**
 * A concrete, resolved navigation opportunity: an [operand] expression whose PHP type dispatches to
 * one or more [targets] of a given [magic] method. Produced by [MagicSites] and consumed by both the
 * gutter line-marker provider and the Goto Declaration handler, so the two entry points stay in
 * lockstep (identical detection + resolution, only the presentation differs).
 */
data class MagicSite(
    val operand: PhpTypedElement,
    val magic: MagicMethod,
    val targets: List<Method>,
)

object MagicMethodResolver {

    /**
     * Resolve an operand expression's PHP type to the magic method(s) it can dispatch to. Returns an
     * empty list for scalars / non-objects / classes that don't declare the method.
     *
     * Steps: take the operand's [com.jetbrains.php.lang.psi.resolve.types.PhpType], complete it via
     * `global()` (turns inferred signatures like `#M...` into concrete FQNs and expands unions),
     * then resolve each FQN through the [PhpIndex] as a class *or* interface and collect its magic
     * method. Interfaces matter for `\Stringable`: a value typed as the interface targets the
     * interface's own (abstract) `__toString` declaration.
     */
    fun resolveMagicTargets(operand: PhpTypedElement, magic: MagicMethod): List<Method> {
        val project = operand.project
        val type = operand.type.global(project)
        val index = PhpIndex.getInstance(project)

        val methods = LinkedHashSet<Method>()
        for (fqn in type.types) {
            // Cheap primitive skip: PHP primitive type strings are lowercase (\string, \int, \bool,
            // \array, …); class/interface FQNs are capitalized (\Money, \Stringable). Empty index
            // results would filter them anyway, but this avoids the lookups on the common scalar path.
            val bare = fqn.substringAfterLast('\\')
            if (bare.isEmpty() || bare[0].isLowerCase()) continue

            val candidates = index.getClassesByFQN(fqn) + index.getInterfacesByFQN(fqn)
            for (klass in candidates) {
                klass.findMethodByName(magic.methodName)?.let { methods.add(it) }
            }
        }
        return methods.toList()
    }

    /** A short label like `\Money::__toString` for tooltips / tests. */
    fun label(method: Method): String =
        "${method.containingClass?.fqn}::${method.name}"
}

/** Deepest-first leaf of an element — the platform contract anchor for line markers. */
internal fun PsiElement.leafAnchor(): PsiElement =
    com.intellij.psi.util.PsiTreeUtil.getDeepestFirst(this)

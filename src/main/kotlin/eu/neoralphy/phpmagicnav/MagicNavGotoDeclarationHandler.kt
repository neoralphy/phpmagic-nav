package eu.neoralphy.phpmagicnav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Method

/**
 * Makes Ctrl+Click / Ctrl+B (Go to Declaration) on the operand of an implicit magic-method site also
 * offer the magic method that runs there - e.g. Ctrl+B on `$m` in `echo $m` adds `Money::__toString`
 * next to the variable's own declaration. Targets are *added* to whatever the platform already
 * resolves, so the user gets a multi-target popup rather than losing the normal jump.
 *
 * Shares [MagicSites] with the gutter provider, so the two entry points always agree on what's a
 * site. The handler walks up from the caret leaf to the nearest enclosing container node and only
 * fires when the caret actually sits inside that node's magic operand.
 */
class MagicNavGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val leaf = sourceElement ?: return null
        val enabled = MagicNavSettings.getInstance().enabledMethods()
        if (enabled.isEmpty()) return null

        val targets = LinkedHashSet<Method>()
        // Climb the ancestor chain: any container whose magic operand contains the caret leaf
        // contributes its target methods. Stop at the enclosing statement to bound the walk.
        var node: PsiElement? = leaf
        while (node != null) {
            for (site in MagicSites.sitesFor(node, enabled)) {
                if (PsiTreeUtil.isAncestor(site.operand, leaf, false)) {
                    targets.addAll(site.targets)
                }
            }
            if (node is com.jetbrains.php.lang.psi.elements.Statement) break
            node = node.parent
        }

        return if (targets.isEmpty()) null else targets.toTypedArray()
    }
}

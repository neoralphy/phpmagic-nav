package eu.neoralphy.phpmagicnav

import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Method

/**
 * Reverse navigation: **Find Usages** on a magic method surfaces the *implicit* invocation sites that
 * trigger it — the `(string)` casts / `echo`s for `__toString`, the `$obj->prop` accesses for
 * `__get`/`__set`, the unresolved `$obj->m()` calls for `__call`, and so on. PhpStorm's own Find
 * Usages only finds explicit textual references to `__toString`, so those implicit sites are normally
 * invisible from the declaration side; this fills the same gap the gutter markers fill, in reverse.
 *
 * It reuses [MagicSites] as the single source of truth, so a site that the gutter marks forward is
 * exactly a site this lists in reverse — the two directions can never disagree.
 *
 * ## How the scan is bounded
 * An implicit site necessarily involves a value whose inferred type is the magic method's class, and
 * that type is only inferable in a file that names the class (a `new C`, a `C` type hint, etc.) — the
 * same precondition the forward markers rely on. So we restrict the (otherwise project-wide) scan to
 * files that contain the class's short name via the identifier index, then run [MagicSites.sitesFor]
 * over each and keep the sites whose target is the very method being searched. Find Usages is a
 * user-initiated, cancellable, backgrounded operation, so this per-file PSI walk is acceptable.
 */
class MagicNavUsageSearcher : CustomUsageSearcher() {

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in Usage>,
        options: FindUsagesOptions,
    ) {
        // Cheap gates first: must be a magic *method* declaration, and the feature must be on.
        val method = element as? Method ?: return
        val magic = MagicMethod.forMethodName(method.name) ?: return
        if (!MagicNavSettings.getInstance().reverseFindUsagesEnabled()) return

        ReadAction.run<RuntimeException> {
            val klass = method.containingClass ?: return@run
            val shortName = klass.name.takeIf { it.isNotEmpty() } ?: return@run
            val project = element.project
            val scope = options.searchScope as? GlobalSearchScope
                ?: GlobalSearchScope.projectScope(project)
            val enabled = setOf(magic)
            val seen = HashSet<PsiElement>()

            PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                shortName, scope,
                { psiFile ->
                    ProgressManager.checkCanceled()
                    if (psiFile is PhpFile) {
                        for (node in PsiTreeUtil.collectElementsOfType(psiFile, PsiElement::class.java)) {
                            for (site in MagicSites.sitesFor(node, enabled)) {
                                val hitsThisMethod = site.targets.any {
                                    element.manager.areElementsEquivalent(it, element)
                                }
                                if (hitsThisMethod && seen.add(site.operand)) {
                                    processor.process(UsageInfo2UsageAdapter(UsageInfo(site.operand)))
                                }
                            }
                        }
                    }
                    true // keep scanning further files
                },
                true, // case-sensitive: PHP class names are case-insensitive but the index is by exact word
            )
        }
    }
}

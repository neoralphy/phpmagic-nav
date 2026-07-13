package eu.neoralphy.phpmagicnav

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.psi.PsiElement

/**
 * Paints a gutter icon at every *implicit* magic-method invocation site (a `(string)` cast, an
 * `echo`/`print`, string interpolation, concatenation, or a `$callable(...)` invoke) that navigates
 * to the operand type's magic method — `__toString()` or `__invoke()` — a jump PhpStorm does not
 * offer natively. A union that dispatches to several targets opens a multi-target popup.
 *
 * All detection + resolution lives in [MagicSites]; this class is only presentation + the two
 * cross-cutting concerns the daemon requires: the settings kill-switch and per-anchor **dedup**.
 */
class MagicNavLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName(): String = "PHP magic-method navigation"

    override fun getIcon() = MagicNavIcons.Gutter

    /**
     * Batch entry point (the platform calls this once per slow-pass chunk). Overriding it — rather
     * than the per-element hook — is what lets us **dedup by anchor**: if two container nodes would
     * anchor a marker on the same leaf, only the first wins, so `echo`-with-multiple-args and nested
     * casts never double-mark the same gutter line.
     */
    override fun collectNavigationMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
        forNavigation: Boolean,
    ) {
        val enabled = MagicNavSettings.getInstance().enabledMethods()
        if (enabled.isEmpty()) return

        val markedAnchors = HashSet<PsiElement>()
        for (element in elements) {
            for (site in MagicSites.sitesFor(element, enabled)) {
                val anchor = site.operand.leafAnchor()
                if (!markedAnchors.add(anchor)) continue // already marked at this leaf
                result.add(buildMarker(site, anchor))
            }
        }
    }

    private fun buildMarker(site: MagicSite, anchor: PsiElement): RelatedItemLineMarkerInfo<PsiElement> {
        val targetClasses = site.targets.joinToString(", ") { it.containingClass?.name ?: "?" }
        return NavigationGutterIconBuilder.create(MagicNavIcons.Gutter)
            .setTargets(site.targets)
            .setTooltipText("Navigate to ${site.magic.display} in $targetClasses")
            .setPopupTitle("Magic method: ${site.magic.display}")
            .createLineMarkerInfo(anchor)
    }
}

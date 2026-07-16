package eu.neoralphy.phpmagicnav

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Method

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
     * than the per-element hook — is what lets us **group by anchor**: all sites that would mark the
     * same leaf collapse into one gutter marker.
     *
     * Grouping (not first-wins) matters because two container nodes can legitimately produce sites on
     * the *same* operand leaf that carry *different* targets — e.g. `echo $o->missing` where `missing`
     * is an undeclared property whose `__get` returns a `Stringable` yields both a `__get` site (the
     * property access) and a `__toString` site (the string coercion of the returned value); and a
     * read-modify-write `$o->missing += 1` yields both `__get` and `__set`. A naive first-wins dedup
     * would silently drop one of the two jumps. Merging their targets into one multi-target marker
     * keeps both jumps while still collapsing genuine duplicates (echo+concat both marking `$m` via
     * `__toString`, nested casts) to a single marker.
     */
    override fun collectNavigationMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
        forNavigation: Boolean,
    ) {
        val enabled = MagicNavSettings.getInstance().enabledMethods()
        if (enabled.isEmpty()) return

        val byAnchor = LinkedHashMap<PsiElement, MutableList<MagicSite>>()
        for (element in elements) {
            for (site in MagicSites.sitesFor(element, enabled)) {
                byAnchor.getOrPut(site.operand.leafAnchor()) { mutableListOf() }.add(site)
            }
        }
        for ((anchor, sites) in byAnchor) {
            result.add(buildMarker(sites, anchor))
        }
    }

    private fun buildMarker(sites: List<MagicSite>, anchor: PsiElement): RelatedItemLineMarkerInfo<PsiElement> {
        // Union the targets of every site anchored here, order-preserving and de-duplicated (the same
        // target can arrive from more than one site — e.g. echo+concat both point at `Money::__toString`).
        val targets = LinkedHashSet<Method>()
        for (site in sites) targets.addAll(site.targets)
        val magics = sites.map { it.magic }.distinct()

        val targetClasses = targets.mapTo(LinkedHashSet()) { it.containingClass?.name ?: "?" }.joinToString(", ")
        val magicDisplay = magics.joinToString(", ") { it.display }
        val popupTitle = if (magics.size == 1) "Magic method: ${magics[0].display}" else "Magic methods"
        return NavigationGutterIconBuilder.create(MagicNavIcons.Gutter)
            .setTargets(targets.toList())
            .setTooltipText("Navigate to $magicDisplay in $targetClasses")
            .setPopupTitle(popupTitle)
            .createLineMarkerInfo(anchor)
    }
}

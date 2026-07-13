package eu.neoralphy.phpmagicnav

import com.intellij.openapi.util.IconLoader

/** Plugin icons. Loaded lazily so a missing resource fails loudly at first use, not at class-load. */
object MagicNavIcons {
    /** The gutter icon shown at implicit magic-method invocation sites. */
    @JvmField
    val Gutter = IconLoader.getIcon("/icons/magicNav.svg", MagicNavIcons::class.java)
}

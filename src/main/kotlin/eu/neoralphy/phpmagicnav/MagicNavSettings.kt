package eu.neoralphy.phpmagicnav

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level, personal settings for which implicit magic-method navigations are shown.
 * A master [State.enabled] short-circuits everything (the performance/UX kill switch); the two
 * per-method toggles let a user keep, say, `__toString` markers while turning off `__invoke`.
 */
@Service(Service.Level.APP)
@State(name = "PhpMagicNavSettings", storages = [Storage("phpMagicNav.xml")])
class MagicNavSettings : SimplePersistentStateComponent<MagicNavSettings.State>(State()) {

    class State : BaseState() {
        var enabled: Boolean by property(true)
        var markToString: Boolean by property(true)
        var markInvoke: Boolean by property(true)
    }

    /** The set of magic methods to detect right now — empty when the master switch is off. */
    fun enabledMethods(): Set<MagicMethod> {
        if (!state.enabled) return emptySet()
        val set = LinkedHashSet<MagicMethod>(2)
        if (state.markToString) set.add(MagicMethod.TO_STRING)
        if (state.markInvoke) set.add(MagicMethod.INVOKE)
        return set
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var markToString: Boolean
        get() = state.markToString
        set(value) { state.markToString = value }

    var markInvoke: Boolean
        get() = state.markInvoke
        set(value) { state.markInvoke = value }

    companion object {
        fun getInstance(): MagicNavSettings = service()
    }
}

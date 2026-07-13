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
        var markGet: Boolean by property(true)
        var markSet: Boolean by property(true)
        var markCall: Boolean by property(true)
        var markCallStatic: Boolean by property(true)
        // Reverse Find Usages: surface implicit invocation sites when Find Usages runs on a magic
        // method. Independent of the marker toggles (a different UX surface), so it has its own flag.
        var reverseFindUsages: Boolean by property(true)
    }

    /** The set of magic methods to detect right now — empty when the master switch is off. */
    fun enabledMethods(): Set<MagicMethod> {
        if (!state.enabled) return emptySet()
        val set = LinkedHashSet<MagicMethod>(MagicMethod.entries.size)
        if (state.markToString) set.add(MagicMethod.TO_STRING)
        if (state.markInvoke) set.add(MagicMethod.INVOKE)
        if (state.markGet) set.add(MagicMethod.GET)
        if (state.markSet) set.add(MagicMethod.SET)
        if (state.markCall) set.add(MagicMethod.CALL)
        if (state.markCallStatic) set.add(MagicMethod.CALL_STATIC)
        return set
    }

    /** Whether reverse Find Usages is active (master switch AND its own toggle). */
    fun reverseFindUsagesEnabled(): Boolean = state.enabled && state.reverseFindUsages

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var markToString: Boolean
        get() = state.markToString
        set(value) { state.markToString = value }

    var markInvoke: Boolean
        get() = state.markInvoke
        set(value) { state.markInvoke = value }

    var markGet: Boolean
        get() = state.markGet
        set(value) { state.markGet = value }

    var markSet: Boolean
        get() = state.markSet
        set(value) { state.markSet = value }

    var markCall: Boolean
        get() = state.markCall
        set(value) { state.markCall = value }

    var markCallStatic: Boolean
        get() = state.markCallStatic
        set(value) { state.markCallStatic = value }

    var reverseFindUsages: Boolean
        get() = state.reverseFindUsages
        set(value) { state.reverseFindUsages = value }

    companion object {
        fun getInstance(): MagicNavSettings = service()
    }
}

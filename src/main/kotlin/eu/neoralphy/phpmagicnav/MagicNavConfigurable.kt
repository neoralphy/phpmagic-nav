package eu.neoralphy.phpmagicnav

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.psi.PsiManager
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

/**
 * Settings page under Settings | Tools | PHP Magic Nav. A master switch gates two per-method
 * toggles. On apply we restart the daemon so gutter markers appear/disappear immediately.
 */
class MagicNavConfigurable : BoundConfigurable("PHP Magic Nav") {

    private val settings get() = MagicNavSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        lateinit var master: Cell<javax.swing.JCheckBox>
        row {
            master = checkBox("Enable magic-method gutter navigation")
                .bindSelected(settings::enabled)
            comment("Adds gutter icons and Go to Declaration targets at implicit magic-method sites.")
        }
        indent {
            row {
                checkBox("__toString()  — (string) cast, echo, print, interpolation, concatenation")
                    .bindSelected(settings::markToString)
                    .enabledIf(master.selected)
            }
            row {
                checkBox("__invoke()  — dynamic \$callable(...) invocations")
                    .bindSelected(settings::markInvoke)
                    .enabledIf(master.selected)
            }
            row {
                checkBox("__get()  — \$obj->prop read of an undeclared property")
                    .bindSelected(settings::markGet)
                    .enabledIf(master.selected)
            }
            row {
                checkBox("__set()  — \$obj->prop = … write to an undeclared property")
                    .bindSelected(settings::markSet)
                    .enabledIf(master.selected)
            }
            row {
                checkBox("__call()  — \$obj->method(...) call of an undeclared method")
                    .bindSelected(settings::markCall)
                    .enabledIf(master.selected)
            }
            row {
                checkBox("__callStatic()  — Foo::method(...) call of an undeclared static method")
                    .bindSelected(settings::markCallStatic)
                    .enabledIf(master.selected)
            }
        }
        row {
            checkBox("Reverse Find Usages: show implicit sites when finding usages of a magic method")
                .bindSelected(settings::reverseFindUsages)
                .enabledIf(master.selected)
            comment(
                "Find Usages on a __toString/__get/__call etc. also lists the (string) casts, " +
                    "property accesses and calls that implicitly trigger it.",
            )
        }
    }

    override fun apply() {
        super.apply()
        // Re-run highlighting so markers reflect the new settings immediately. Restart per open file
        // (the non-deprecated `restart(PsiFile, reason)` overload); fall back to a whole-daemon
        // restart only when a project has no open editors.
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            var restartedAny = false
            for (vFile in FileEditorManager.getInstance(project).openFiles) {
                val psiFile = if (vFile.isValid) psiManager.findFile(vFile) else null
                if (psiFile != null) {
                    daemon.restart(psiFile, REASON)
                    restartedAny = true
                }
            }
            if (!restartedAny) daemon.restart(REASON)
        }
    }

    private companion object {
        const val REASON = "PHP Magic Nav: settings change"
    }
}

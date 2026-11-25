package dev.e2m.ui

import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.e2m.core.E2mPaths
import dev.e2m.debug.DebugBridge
import dev.e2m.debug.E2mCommands
import dev.e2m.session.E2mSession
import dev.e2m.session.E2mSessionStore
import dev.e2m.session.E2mStep
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replays a recorded session.
 *  - Static: walks the steps, showing the recorded narration and values. No debugger needed.
 *  - Live: re-runs the recorded run configuration under the debugger and re-executes each step's
 *    commands (breakpoints, steps, resumes) before showing it, with values re-evaluated live.
 */
@Service(Service.Level.PROJECT)
class E2mReplayer(private val project: Project) {
    @Volatile var session: E2mSession? = null; private set
    @Volatile var index: Int = -1; private set
    @Volatile var live: Boolean = false; private set
    private val busy = AtomicBoolean(false)
    val isBusy get() = busy.get()

    private var disabledByReplay: List<XLineBreakpoint<*>> = emptyList()
    private var breakpointsBefore: Set<Pair<String, Int>> = emptySet()

    private val presenter get() = E2mPresenter.getInstance(project)
    private val bridge get() = DebugBridge.getInstance(project)

    fun load(name: String): E2mSession {
        if (live) stopLive()
        val s = E2mSessionStore.load(project, name)
        session = s
        index = -1
        presenter.status("Loaded '${s.title ?: s.name}' — ${s.steps().size} steps")
        return s
    }

    fun hasNext() = session?.let { index + 1 < it.steps().size } ?: false
    fun hasPrev() = !live && index > 0

    fun next() = if (live) background { liveStep() } else goTo(index + 1)
    fun prev() { if (!live) goTo(index - 1) }

    fun goTo(i: Int) {
        val s = session ?: return
        if (i !in s.steps().indices) return
        val step = s.steps()[i]
        background {
            index = i
            val evals = step.evaluations.orEmpty().map { it.expression to (it.value ?: it.error?.let { e -> com.google.gson.JsonPrimitive("error: $e") }) }
            showStep(step, i, s, "replay", step.variables, evals)
        }
    }

    fun startLive() {
        val s = session ?: return
        background {
            breakpointsBefore = currentBreakpoints()
            val sessionBps = s.steps().flatMap { it.commands.orEmpty() }
                .filter { it.action == "set_breakpoint" }
                .mapNotNull { c -> runCatching { E2mCommands.resolveFile(project, c.file).url to E2mCommands.resolveLine(project, E2mCommands.resolveFile(project, c.file), c.line, c.anchor) }.getOrNull() }
                .toSet()
            disabledByReplay = bridge.disableAllExcept(sessionBps)
            live = true
            index = -1
            presenter.status("Live debug of '${s.title ?: s.name}' — starting debugger…")
            liveStep()
        }
    }

    private fun liveStep() {
        val s = session ?: return
        val i = index + 1
        if (i !in s.steps().indices) { finishLive("Live debug finished"); return }
        val step = s.steps()[i]
        for (cmd in step.commands.orEmpty()) {
            if (cmd.action == "start" && cmd.configuration.isNullOrBlank()) cmd.configuration = s.runConfiguration
            presenter.status("Step ${i + 1}: ${cmd.action}${cmd.file?.let { " $it:${cmd.line}" } ?: ""}")
            val r = runCatching { E2mCommands.execute(project, cmd) }
            r.exceptionOrNull()?.let { finishLive("Live debug stopped: ${it.message}"); return }
            if (r.getOrNull()?.outcome == DebugBridge.STOPPED && cmd.action != "stop") {
                finishLive("Program finished before step ${i + 1}"); return
            }
        }
        index = i
        val paused = bridge.currentPosition() != null
        val vars: JsonElement? = if (paused) runCatching { bridge.variables(0, 0) }.getOrNull() else step.variables
        val evals = step.evaluations.orEmpty().map { e ->
            e.expression to (if (paused) runCatching<JsonElement> { bridge.evaluate(e.expression, 0, 0) }
                .getOrElse { com.google.gson.JsonPrimitive("error: ${it.message}") } else e.value)
        }
        showStep(step, i, s, "live debug", vars, evals)
        presenter.status("Live debug: step ${i + 1}/${s.steps().size}" + if (i + 1 == s.steps().size) " (last)" else "")
    }

    fun stopLive() = background { finishLive("Live debug stopped") }

    private fun finishLive(message: String) {
        if (!live) return
        live = false
        runCatching { bridge.restoreEnabled(disabledByReplay) }
        disabledByReplay = emptyList()
        // Remove breakpoints that only the replay added.
        val added = currentBreakpoints() - breakpointsBefore
        added.forEach { (file, line) -> runCatching { bridge.removeBreakpoint(E2mPaths.resolveFile(project, file), line - 1) } }
        runCatching { if (bridge.session() != null) bridge.stop() }
        presenter.status(message)
    }

    private fun currentBreakpoints(): Set<Pair<String, Int>> =
        bridge.breakpoints().map { it.asJsonObject.let { o -> o["file"].asString to o["line"].asInt } }.toSet()

    private fun showStep(step: E2mStep, i: Int, s: E2mSession, source: String, vars: JsonElement?, evals: List<Pair<String, JsonElement?>>) {
        val file = step.file?.let { E2mPaths.resolveFile(project, it) }
        val line0 = file?.let { runCatching { E2mCommands.resolveLine(project, it, step.line, step.anchor) }.getOrNull() }
        val view = StepView(
            source = source, index = i, total = s.steps().size, title = step.title, text = step.text,
            location = step.file?.let { "$it:${(line0 ?: (step.line - 1)) + 1}" },
            details = E2mPresenter.describe(vars, evals),
        )
        ApplicationManager.getApplication().invokeAndWait({ if (!project.isDisposed) presenter.show(view, file, line0) }, com.intellij.openapi.application.ModalityState.nonModal())
    }

    private fun background(block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { block() } catch (t: Throwable) { presenter.status("Error: ${t.message}") } finally {
                busy.set(false)
                presenter.status(presenter.statusLine) // refresh buttons
            }
        }
    }

    companion object {
        fun getInstance(project: Project): E2mReplayer = project.service()
    }
}

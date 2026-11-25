package dev.e2m.debug

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import dev.e2m.core.E2mPaths
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class E2mException(message: String) : RuntimeException(message)

/**
 * Thin, synchronous facade over the platform XDebugger API, language agnostic (works for any debugger
 * that plugs into XDebugger: Java/Kotlin, Go, Python, JS, PHP, ...).
 *
 * All public methods are meant to be called from a background (server) thread.
 */
@Service(Service.Level.PROJECT)
class DebugBridge(private val project: Project) : Disposable {
    private val waiters = CopyOnWriteArrayList<CompletableFuture<String>>()
    private val attached: MutableSet<XDebugSession> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    init {
        project.messageBus.connect(this).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) = attach(debugProcess.session)
            override fun processStopped(debugProcess: XDebugProcess) = fire(STOPPED)
        })
        project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) = fire(NOT_STARTED)
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                lastLaunchError = cause?.message
                fire(NOT_STARTED)
            }
        })
        XDebuggerManager.getInstance(project).debugSessions.forEach { attach(it) }
    }

    @Volatile
    private var lastLaunchError: String? = null

    private fun attach(session: XDebugSession) {
        if (!attached.add(session)) return
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = fire(PAUSED)
            override fun sessionStopped() = fire(STOPPED)
        }, this)
    }

    private fun fire(event: String) {
        val current = waiters.toList()
        waiters.removeAll(current)
        current.forEach { it.complete(event) }
    }

    /** Runs [action] on the EDT and blocks until the debugger pauses or stops (or the timeout hits). */
    private fun runAndWait(timeoutMs: Long, wait: Boolean = true, action: () -> Unit): String {
        val f = CompletableFuture<String>()
        if (wait) waiters += f
        edt(action)
        if (!wait) return RUNNING
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            waiters.remove(f)
            RUNNING
        }
    }

    private fun edt(action: () -> Unit) {
        var error: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait({
            try { action() } catch (t: Throwable) { error = t }
        }, ModalityState.nonModal())
        error?.let { throw it as? E2mException ?: E2mException(it.message ?: it.toString()) }
    }

    // ---------------------------------------------------------------- sessions

    fun session(): XDebugSession? {
        val mgr = XDebuggerManager.getInstance(project)
        return mgr.currentSession?.takeIf { !it.isStopped } ?: mgr.debugSessions.firstOrNull { !it.isStopped }
    }

    private fun requireSession(): XDebugSession = session() ?: throw E2mException("No active debug session. Start one with e2m_start_debug.")

    private fun requirePaused(): XDebugSession = requireSession().also {
        if (!it.isSuspended) throw E2mException("The debugger is running, not paused. Use e2m_step with action=pause, or wait for a breakpoint.")
    }

    fun runConfigurations(): JsonArray {
        val rm = RunManager.getInstance(project)
        val arr = JsonArray()
        rm.allSettings.forEach { s ->
            arr.add(JsonObject().apply {
                addProperty("name", s.name)
                addProperty("type", s.type.displayName)
                addProperty("selected", rm.selectedConfiguration == s)
            })
        }
        return arr
    }

    fun startDebug(configuration: String?, timeoutMs: Long): String {
        val rm = RunManager.getInstance(project)
        val settings = if (configuration.isNullOrBlank()) {
            rm.selectedConfiguration ?: throw E2mException("No run configuration selected; pass 'configuration' (see e2m_list_run_configurations).")
        } else {
            rm.findConfigurationByName(configuration)
                ?: throw E2mException("Run configuration '$configuration' not found. Available: ${rm.allSettings.joinToString { it.name }}")
        }
        lastLaunchError = null
        val outcome = runAndWait(timeoutMs) {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }
        if (outcome == NOT_STARTED) {
            throw E2mException("'${settings.name}' could not be started" + (lastLaunchError?.let { ": $it" } ?: " (see the IDE's Run/Build tool window)"))
        }
        return outcome
    }

    fun stop() {
        val s = requireSession()
        edt { s.stop() }
    }

    fun step(action: String, wait: Boolean, timeoutMs: Long): String {
        val s = if (action == "pause") requireSession() else requirePaused()
        return runAndWait(timeoutMs, wait) {
            when (action) {
                "over" -> s.stepOver(false)
                "into" -> s.stepInto()
                "out" -> s.stepOut()
                "resume" -> s.resume()
                "pause" -> s.pause()
                else -> throw E2mException("Unknown step action '$action' (use over, into, out, resume, pause)")
            }
        }
    }

    fun runToLine(file: VirtualFile, line0: Int, timeoutMs: Long): String {
        val s = requirePaused()
        val pos = XDebuggerUtil.getInstance().createPosition(file, line0) ?: throw E2mException("Invalid position")
        return runAndWait(timeoutMs) { s.runToPosition(pos, false) }
    }

    // ---------------------------------------------------------------- breakpoints

    private fun lineBreakpoints(): List<XLineBreakpoint<*>> =
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()

    fun setBreakpoint(file: VirtualFile, line0: Int, condition: String?): JsonObject {
        var result: JsonObject? = null
        edt {
            WriteAction.run<Throwable> {
                lineBreakpoints().firstOrNull { it.fileUrl == file.url && it.line == line0 }?.let {
                    if (!condition.isNullOrBlank()) it.conditionExpression = expression(condition)
                    result = breakpointJson(it).apply { addProperty("alreadyExisted", true) }
                    return@run
                }
                val type = XDebuggerUtil.getInstance().lineBreakpointTypes
                    .filter { it.canPutAt(file, line0, project) }
                    .maxByOrNull { it.priority }
                    ?: throw E2mException("Cannot put a breakpoint at ${file.name}:${line0 + 1} (no debugger supports this line — is it code?)")
                @Suppress("UNCHECKED_CAST")
                val t = type as XLineBreakpointType<XBreakpointProperties<*>>
                val bp = XDebuggerManager.getInstance(project).breakpointManager
                    .addLineBreakpoint(t, file.url, line0, t.createBreakpointProperties(file, line0))
                if (!condition.isNullOrBlank()) bp.conditionExpression = expression(condition)
                result = breakpointJson(bp)
            }
        }
        return result!!
    }

    private fun expression(text: String) = XDebuggerUtil.getInstance().createExpression(text, null, null, EvaluationMode.EXPRESSION)

    fun removeBreakpoint(file: VirtualFile?, line0: Int?): Int {
        var removed = 0
        edt {
            WriteAction.run<Throwable> {
                val mgr = XDebuggerManager.getInstance(project).breakpointManager
                lineBreakpoints()
                    .filter { file == null || (it.fileUrl == file.url && (line0 == null || it.line == line0)) }
                    .forEach { mgr.removeBreakpoint(it); removed++ }
            }
        }
        return removed
    }

    /** Enables/disables every line breakpoint not in [keep]; returns the ones it changed, for [restoreEnabled]. */
    fun disableAllExcept(keep: Set<Pair<String, Int>>): List<XLineBreakpoint<*>> {
        val changed = ArrayList<XLineBreakpoint<*>>()
        edt {
            lineBreakpoints().filter { it.isEnabled && (it.fileUrl to it.line) !in keep }.forEach {
                it.isEnabled = false; changed += it
            }
        }
        return changed
    }

    fun restoreEnabled(bps: List<XLineBreakpoint<*>>) = edt { bps.forEach { it.isEnabled = true } }

    @Suppress("DEPRECATION")
    fun breakpoints(): JsonArray = JsonArray().also { arr -> runReadAction { lineBreakpoints().forEach { arr.add(breakpointJson(it)) } } }

    private fun breakpointJson(bp: XLineBreakpoint<*>): JsonObject = JsonObject().apply {
        val vf = VirtualFileManager.getInstance().findFileByUrl(bp.fileUrl)
        addProperty("file", vf?.let { E2mPaths.relative(project, it) } ?: bp.fileUrl)
        addProperty("line", bp.line + 1)
        addProperty("enabled", bp.isEnabled)
        bp.conditionExpression?.expression?.let { addProperty("condition", it) }
        vf?.let { f -> lineText(f, bp.line)?.let { addProperty("code", it) } }
    }

    // ---------------------------------------------------------------- inspection

    fun frames(limit: Int = 30): List<XStackFrame> {
        val s = requirePaused()
        val stack: XExecutionStack = s.suspendContext?.activeExecutionStack ?: return listOfNotNull(s.currentStackFrame)
        val f = CompletableFuture<List<XStackFrame>>()
        val acc = ArrayList<XStackFrame>()
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                synchronized(acc) {
                    acc.addAll(stackFrames)
                    if (last || acc.size >= limit) f.complete(acc.toList())
                }
            }

            override fun errorOccurred(errorMessage: String) {
                f.complete(synchronized(acc) { acc.toList() })
            }

            override fun isObsolete() = false
        })
        val frames = runCatching { f.get(5, TimeUnit.SECONDS) }.getOrElse { synchronized(acc) { acc.toList() } }
        return frames.ifEmpty { listOfNotNull(stack.topFrame) }.take(limit)
    }

    private fun frame(index: Int): XStackFrame {
        if (index == 0) {
            val s = requirePaused()
            (s.currentStackFrame ?: s.suspendContext?.activeExecutionStack?.topFrame)?.let { return it }
        }
        return frames(index + 1).getOrNull(index) ?: throw E2mException("No stack frame #$index")
    }

    fun framesJson(): JsonArray = JsonArray().also { arr ->
        frames().forEachIndexed { i, fr ->
            arr.add(JsonObject().apply {
                addProperty("index", i)
                addProperty("frame", Values.frameText(fr))
                fr.sourcePosition?.let { add("position", positionJson(it.file, it.line)) }
            })
        }
    }

    fun variables(frameIndex: Int, depth: Int): JsonArray {
        val fr = frame(frameIndex)
        return JsonArray().also { arr -> Values.children(fr).forEach { arr.add(Values.toJson(it.name, it.value, depth)) } }
    }

    fun evaluate(expression: String, frameIndex: Int, depth: Int): JsonObject {
        val fr = frame(frameIndex)
        val evaluator = fr.evaluator ?: throw E2mException("This debugger does not support expression evaluation here")
        val f = CompletableFuture<XValue>()
        edt {
            evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) { f.complete(result) }
                override fun errorOccurred(errorMessage: String) { f.completeExceptionally(E2mException(errorMessage)) }
            }, fr.sourcePosition)
        }
        val value = try {
            f.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause as? E2mException ?: E2mException(e.cause?.message ?: "evaluation failed")
        } catch (_: TimeoutException) {
            throw E2mException("Evaluation of '$expression' timed out")
        }
        return Values.toJson(expression, value, depth)
    }

    /** Current debugger state, used as the response of every stepping command. */
    fun state(includeVariables: Boolean = true, depth: Int = 0): JsonObject = JsonObject().apply {
        val s = session()
        when {
            s == null -> addProperty("status", "no_session")
            !s.isSuspended -> {
                addProperty("status", "running")
                addProperty("session", s.sessionName)
            }
            else -> {
                addProperty("status", "paused")
                addProperty("session", s.sessionName)
                s.currentPosition?.let { add("position", positionJson(it.file, it.line)) }
                s.currentStackFrame?.let { addProperty("frame", Values.frameText(it)) }
                if (includeVariables) runCatching { add("variables", variables(0, depth)) }
                    .onFailure { addProperty("variablesError", it.message) }
            }
        }
    }

    fun currentPosition(): XSourcePosition? = session()?.takeIf { it.isSuspended }?.currentPosition

    fun positionJson(file: VirtualFile, line0: Int): JsonObject = JsonObject().apply {
        addProperty("file", E2mPaths.relative(project, file))
        addProperty("line", line0 + 1)
        lineText(file, line0)?.let { addProperty("code", it) }
    }

    @Suppress("DEPRECATION")
    fun lineText(file: VirtualFile, line0: Int): String? = runReadAction {
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction null
        if (line0 < 0 || line0 >= doc.lineCount) return@runReadAction null
        doc.charsSequence.subSequence(doc.getLineStartOffset(line0), doc.getLineEndOffset(line0)).toString().trim()
    }

    override fun dispose() {
        fire(STOPPED)
    }

    companion object {
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
        const val RUNNING = "running"
        const val NOT_STARTED = "not_started"
        fun getInstance(project: Project): DebugBridge = project.service()
    }
}

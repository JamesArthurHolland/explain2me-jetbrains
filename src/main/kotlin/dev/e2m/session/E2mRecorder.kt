package dev.e2m.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Records debugger commands and explanations issued through the server into an [E2mSession].
 * Commands accumulate until the next explanation, which turns them into a step.
 */
@Service(Service.Level.PROJECT)
class E2mRecorder(private val project: Project) {
    @Volatile
    var session: E2mSession? = null
        private set
    private val pendingCommands = mutableListOf<E2mCommand>()
    private val pendingEvaluations = mutableListOf<E2mEvaluation>()

    val isRecording get() = session != null

    @Synchronized
    fun start(name: String, title: String?, description: String?, runConfiguration: String?): E2mSession {
        pendingCommands.clear()
        pendingEvaluations.clear()
        return E2mSession(
            name = name, title = title, description = description, runConfiguration = runConfiguration,
            createdAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        ).also { session = it }
    }

    @Synchronized
    fun record(command: E2mCommand) {
        val s = session ?: return
        if (command.action == "start" && s.runConfiguration == null) s.runConfiguration = command.configuration
        pendingCommands += command
    }

    @Synchronized
    fun recordEvaluation(e: E2mEvaluation) {
        if (session != null) pendingEvaluations += e
    }

    /** Adds a step and returns its index, or -1 when not recording. */
    @Synchronized
    fun addStep(step: E2mStep): Int {
        val s = session ?: return -1
        step.commands = pendingCommands.toMutableList()
        step.evaluations = (step.evaluations.orEmpty() + pendingEvaluations).toMutableList()
        pendingCommands.clear()
        pendingEvaluations.clear()
        s.steps().add(step)
        return s.steps().size - 1
    }

    /** Keeps recorded line numbers valid when e2m comments are inserted into a file while recording. */
    @Synchronized
    fun shiftLines(file: String, fromLine1: Int, delta: Int) {
        val s = session ?: return
        fun shift(l: Int?) = if (l != null && l >= fromLine1) l + delta else l
        val cmds = s.steps().flatMap { it.commands.orEmpty() } + pendingCommands
        cmds.filter { it.file == file }.forEach { it.line = shift(it.line) }
        s.steps().filter { it.file == file }.forEach { it.line = shift(it.line)!! }
    }

    @Synchronized
    fun save(): java.nio.file.Path {
        val s = session ?: throw IllegalStateException("Not recording. Call e2m_session_start first.")
        if (pendingCommands.isNotEmpty()) {
            // Trailing commands after the last explanation are kept as a final, silent step.
            addStep(E2mStep(title = "(end)", text = ""))
        }
        return E2mSessionStore.save(project, s)
    }

    @Synchronized
    fun stop(): E2mSession? = session.also { session = null; pendingCommands.clear(); pendingEvaluations.clear() }

    companion object {
        fun getInstance(project: Project): E2mRecorder = project.service()
    }
}

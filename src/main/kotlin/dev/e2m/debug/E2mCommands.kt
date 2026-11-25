package dev.e2m.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.e2m.core.E2mPaths
import dev.e2m.session.E2mCommand

/** Executes recorded/requested [E2mCommand]s. Shared by the server tools and live debug. */
object E2mCommands {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val START_TIMEOUT_MS = 180_000L

    data class Result(val outcome: String, val command: E2mCommand)

    fun resolveFile(project: Project, file: String?): VirtualFile {
        if (file.isNullOrBlank()) throw E2mException("'file' is required")
        return E2mPaths.resolveFile(project, file) ?: throw E2mException("File not found: $file")
    }

    /**
     * Returns the 0-based line for a 1-based [line1]. If an [anchor] (trimmed source text) is given and the
     * line no longer matches it, the nearest line with that text (within 200 lines) is used instead, so
     * sessions survive small edits to the code.
     */
    fun resolveLine(project: Project, file: VirtualFile, line1: Int?, anchor: String?): Int {
        if (line1 == null || line1 < 1) throw E2mException("'line' (1-based) is required")
        val line0 = line1 - 1
        if (anchor.isNullOrBlank()) return line0
        val bridge = DebugBridge.getInstance(project)
        if (bridge.lineText(file, line0) == anchor) return line0
        for (d in 1..200) {
            if (bridge.lineText(file, line0 - d) == anchor) return line0 - d
            if (bridge.lineText(file, line0 + d) == anchor) return line0 + d
        }
        return line0
    }

    /** Executes [cmd]; fills in [E2mCommand.anchor] for positional commands so the recording is robust. */
    fun execute(project: Project, cmd: E2mCommand, timeoutMs: Long? = null, wait: Boolean = true): Result {
        val bridge = DebugBridge.getInstance(project)
        val outcome = when (cmd.action) {
            "start" -> bridge.startDebug(cmd.configuration, timeoutMs ?: START_TIMEOUT_MS)
            "stop" -> { bridge.stop(); DebugBridge.STOPPED }
            "over", "into", "out", "resume", "pause" -> bridge.step(cmd.action, wait, timeoutMs ?: DEFAULT_TIMEOUT_MS)
            "set_breakpoint", "remove_breakpoint", "run_to_line" -> {
                val vf = resolveFile(project, cmd.file)
                val line0 = resolveLine(project, vf, cmd.line, cmd.anchor)
                cmd.file = E2mPaths.relative(project, vf)
                cmd.line = line0 + 1
                cmd.anchor = bridge.lineText(vf, line0)
                when (cmd.action) {
                    "set_breakpoint" -> { bridge.setBreakpoint(vf, line0, cmd.condition); "ok" }
                    "remove_breakpoint" -> { bridge.removeBreakpoint(vf, line0); "ok" }
                    else -> bridge.runToLine(vf, line0, timeoutMs ?: DEFAULT_TIMEOUT_MS)
                }
            }
            else -> throw E2mException("Unknown command '${cmd.action}'")
        }
        return Result(outcome, cmd)
    }
}

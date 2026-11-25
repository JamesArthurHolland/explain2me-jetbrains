package dev.e2m.session

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.intellij.openapi.project.Project
import dev.e2m.core.E2mPaths
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * A replayable explanation, stored as `.e2m/sessions/<name>.json`.
 *
 * A session is an ordered list of [E2mStep]s. Each step says *where* (file/line) and *what* (the narration),
 * plus the debugger [E2mCommand]s that were issued to get there since the previous step. Replaying
 * "statically" just walks the steps; replaying "live" re-executes the commands against a fresh debug run,
 * so the same breakpoints/steps happen again and the values can be re-checked.
 *
 * All line numbers are 1-based, all paths are relative to the project root.
 */
class E2mSession(
    var version: Int = 1,
    var name: String = "",
    var title: String? = null,
    var description: String? = null,
    /** Run configuration used for live replay. */
    var runConfiguration: String? = null,
    var createdAt: String? = null,
    var steps: MutableList<E2mStep>? = mutableListOf(),
) {
    fun steps(): MutableList<E2mStep> = steps ?: mutableListOf<E2mStep>().also { steps = it }
}

class E2mStep(
    var title: String? = null,
    var file: String? = null,
    var line: Int = 0,
    /** Trimmed source text of [line] at record time; used to re-locate the line if the code moved. */
    var anchor: String? = null,
    var text: String = "",
    var commands: MutableList<E2mCommand>? = mutableListOf(),
    /** Variables visible when the step was recorded (static replay shows these). */
    var variables: JsonElement? = null,
    var evaluations: MutableList<E2mEvaluation>? = mutableListOf(),
)

/**
 * One debugger action. `action` is one of:
 * `start` (configuration), `set_breakpoint` (file, line, condition), `remove_breakpoint` (file, line),
 * `over`, `into`, `out`, `resume`, `pause`, `run_to_line` (file, line), `stop`.
 */
class E2mCommand(
    var action: String = "",
    var file: String? = null,
    var line: Int? = null,
    var anchor: String? = null,
    var condition: String? = null,
    var configuration: String? = null,
)

class E2mEvaluation(
    var expression: String = "",
    var value: JsonElement? = null,
    var error: String? = null,
)

object E2mSessionStore {
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()!!

    fun list(project: Project): List<String> {
        val dir = E2mPaths.sessionsDir(project)
        if (!Files.isDirectory(dir)) return emptyList()
        return dir.listDirectoryEntries("*.json").map { it.nameWithoutExtension }.sorted()
    }

    fun load(project: Project, name: String): E2mSession {
        val path = E2mPaths.sessionPath(project, name)
        require(Files.exists(path)) { "No session '$name' (looked for $path)" }
        val s = gson.fromJson(Files.readString(path), E2mSession::class.java)
        if (s.name.isBlank()) s.name = name
        s.steps().forEach { st ->
            if (st.commands == null) st.commands = mutableListOf()
            if (st.evaluations == null) st.evaluations = mutableListOf()
        }
        return s
    }

    fun save(project: Project, session: E2mSession): java.nio.file.Path {
        val path = E2mPaths.sessionPath(project, session.name)
        E2mPaths.write(path, gson.toJson(session) + "\n")
        return path
    }
}

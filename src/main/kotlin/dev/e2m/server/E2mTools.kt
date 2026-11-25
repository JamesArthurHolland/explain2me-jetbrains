package dev.e2m.server

import com.google.gson.*
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiManager
import dev.e2m.core.E2mFolding
import dev.e2m.core.E2mPaths
import dev.e2m.debug.DebugBridge
import dev.e2m.debug.E2mCommands
import dev.e2m.debug.E2mException
import dev.e2m.session.*
import dev.e2m.ui.E2mPresenter
import dev.e2m.ui.E2mReplayer
import dev.e2m.ui.StepView
import java.nio.file.Files
import java.nio.file.Paths

/** The tool catalogue shared by the MCP endpoint and the plain HTTP API. */
object E2mTools {
    val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    val INSTRUCTIONS = """
        Explain2Me drives the JetBrains IDE debugger so you can explain code to the user while they watch.
        All line numbers are 1-based; file paths may be absolute or relative to the project root.
        Typical flow:
          1. e2m_status, e2m_list_run_configurations.
          2. e2m_session_start (name like "binary-search") if the walkthrough should be saved for replay.
          3. e2m_set_breakpoint at interesting lines, then e2m_start_debug.
          4. Repeat: inspect (state is returned by every step; e2m_get_variables / e2m_evaluate for more),
             then e2m_explain with a short narration of what this line does and what the values mean,
             then e2m_step (over/into/out/resume) or e2m_run_to_line.
          5. e2m_session_save. The user can replay it from the Explain2Me tool window (static, or as a live debug).
        e2m_annotate writes permanent explanation comments into the code (// e2m :f <note> with a shadow
        note in .e2m/notes, or an inline /* e2m := ... */). Prefer annotating after recording a session.
    """.trimIndent()

    class Tool(val name: String, val description: String, val schema: JsonObject, val handler: (Ctx) -> JsonElement)

    class Ctx(val args: JsonObject) {
        val project: Project by lazy { resolveProject(args) }
        val bridge by lazy { DebugBridge.getInstance(project) }
        val recorder by lazy { E2mRecorder.getInstance(project) }
        fun str(k: String): String? = args.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        fun int(k: String): Int? = args.get(k)?.takeIf { !it.isJsonNull }?.asInt
        fun long(k: String): Long? = args.get(k)?.takeIf { !it.isJsonNull }?.asLong
        fun bool(k: String, default: Boolean): Boolean = args.get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: default
        fun req(k: String): String = str(k) ?: throw E2mException("Missing required argument '$k'")
    }

    // ------------------------------------------------------------------ schema helpers
    private fun prop(type: String, description: String, enum: List<String>? = null) = JsonObject().apply {
        addProperty("type", type); addProperty("description", description)
        enum?.let { add("enum", JsonArray().apply { it.forEach(::add) }) }
    }

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("project", prop("string", "Optional project name or path when several projects are open"))
            props.forEach { (k, v) -> add(k, v) }
        })
        if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach(::add) })
    }

    private val FILE = "file" to prop("string", "Source file, absolute or relative to the project root")
    private val LINE = "line" to prop("integer", "1-based line number")
    private val VARS = "variables" to prop("boolean", "Include the top frame's variables in the returned state (default true)")
    private val DEPTH = "depth" to prop("integer", "How many levels of object children to expand (default 0)")
    private val TIMEOUT = "timeoutMs" to prop("integer", "Max time to wait for the debugger to pause")

    private fun json(block: JsonObject.() -> Unit) = JsonObject().apply(block)

    /** Executes a debugger command, records it if a session is being recorded, returns outcome + state. */
    private fun command(c: Ctx, cmd: E2mCommand, wait: Boolean = true): JsonObject {
        val r = E2mCommands.execute(c.project, cmd, c.long("timeoutMs"), wait)
        c.recorder.record(r.command)
        return json {
            addProperty("outcome", r.outcome)
            add("state", c.bridge.state(c.bool("variables", true), c.int("depth") ?: 0))
        }
    }

    val tools: List<Tool> = listOf(
        Tool("e2m_status", "Overview: open projects, debugger state, breakpoints, recording and saved sessions.", schema()) { c ->
            json {
                add("projects", JsonArray().apply { openProjects().forEach { add(json { addProperty("name", it.name); addProperty("path", it.basePath) }) } })
                addProperty("project", c.project.name)
                addProperty("e2mFolder", E2mPaths.root(c.project).toString())
                add("debugger", c.bridge.state(false))
                add("breakpoints", c.bridge.breakpoints())
                addProperty("recording", c.recorder.session?.name)
                add("sessions", gson.toJsonTree(E2mSessionStore.list(c.project)))
            }
        },
        Tool("e2m_list_run_configurations", "List run configurations that e2m_start_debug can launch.", schema()) { c ->
            c.bridge.runConfigurations()
        },
        Tool(
            "e2m_start_debug",
            "Launch a run configuration under the debugger and wait until it pauses (breakpoint) or finishes. Set breakpoints first.",
            schema("configuration" to prop("string", "Run configuration name (default: the selected one)"), TIMEOUT, VARS, DEPTH),
        ) { c -> command(c, E2mCommand(action = "start", configuration = c.str("configuration"))) },
        Tool("e2m_stop_debug", "Stop the active debug session.", schema()) { c -> command(c, E2mCommand(action = "stop")) },
        Tool(
            "e2m_set_breakpoint", "Add a line breakpoint (optionally conditional).",
            schema(FILE, LINE, "condition" to prop("string", "Optional condition expression in the debuggee language"), required = listOf("file", "line")),
        ) { c ->
            val cmd = E2mCommand(action = "set_breakpoint", file = c.req("file"), line = c.int("line"), condition = c.str("condition"))
            E2mCommands.execute(c.project, cmd).also { c.recorder.record(it.command) }
            json { addProperty("ok", true); add("breakpoints", c.bridge.breakpoints()) }
        },
        Tool(
            "e2m_remove_breakpoint", "Remove line breakpoints: one line, a whole file (omit line) or all (omit file).",
            schema(FILE, LINE),
        ) { c ->
            val file = c.str("file")
            if (file != null && c.int("line") != null) {
                E2mCommands.execute(c.project, E2mCommand(action = "remove_breakpoint", file = file, line = c.int("line")))
                    .also { c.recorder.record(it.command) }
            } else {
                c.bridge.removeBreakpoint(file?.let { E2mCommands.resolveFile(c.project, it) }, null)
            }
            json { addProperty("ok", true); add("breakpoints", c.bridge.breakpoints()) }
        },
        Tool("e2m_list_breakpoints", "List all line breakpoints.", schema()) { c -> c.bridge.breakpoints() },
        Tool(
            "e2m_step",
            "Step the paused debugger and return the new state (position, code line, variables).",
            schema(
                "action" to prop("string", "over | into | out | resume | pause", listOf("over", "into", "out", "resume", "pause")),
                "wait" to prop("boolean", "Wait until the debugger pauses again (default true)"),
                TIMEOUT, VARS, DEPTH, required = listOf("action"),
            ),
        ) { c -> command(c, E2mCommand(action = c.req("action")), c.bool("wait", true)) },
        Tool("e2m_run_to_line", "Resume until the given line is reached.", schema(FILE, LINE, TIMEOUT, VARS, DEPTH, required = listOf("file", "line"))) { c ->
            command(c, E2mCommand(action = "run_to_line", file = c.req("file"), line = c.int("line")))
        },
        Tool("e2m_get_state", "Current debugger state: status, position, code line and top-frame variables.", schema(VARS, DEPTH)) { c ->
            c.bridge.state(c.bool("variables", true), c.int("depth") ?: 0)
        },
        Tool("e2m_get_frames", "The paused thread's call stack.", schema()) { c -> c.bridge.framesJson() },
        Tool(
            "e2m_get_variables", "Variables of a stack frame.",
            schema("frame" to prop("integer", "Frame index from e2m_get_frames (default 0 = top)"), DEPTH),
        ) { c -> c.bridge.variables(c.int("frame") ?: 0, c.int("depth") ?: 1) },
        Tool(
            "e2m_evaluate", "Evaluate an expression in the paused frame (recorded with the next explanation).",
            schema("expression" to prop("string", "Expression in the debuggee's language"), "frame" to prop("integer", "Frame index (default 0)"), DEPTH, required = listOf("expression")),
        ) { c ->
            val expr = c.req("expression")
            try {
                c.bridge.evaluate(expr, c.int("frame") ?: 0, c.int("depth") ?: 0).also {
                    c.recorder.recordEvaluation(E2mEvaluation(expr, it))
                }
            } catch (e: E2mException) {
                c.recorder.recordEvaluation(E2mEvaluation(expr, error = e.message))
                throw e
            }
        },
        Tool(
            "e2m_explain",
            "Show an explanation to the user: highlights the line, draws the narration above it in the editor and in the " +
                "Explain2Me tool window. Defaults to the current debugger position. When recording, this becomes a replay step " +
                "(together with the debugger commands since the previous step). Markdown is supported.",
            schema(
                "text" to prop("string", "The narration (markdown). Explain what happens and why, referencing concrete values."),
                "title" to prop("string", "Short step title"), FILE, LINE,
                "captureVariables" to prop("boolean", "Store current variables with the step for static replay (default true)"),
                "inline" to prop("boolean", "Draw the narration inside the editor above the line (default true)"),
                required = listOf("text"),
            ),
        ) { c ->
            val project = c.project
            val text = c.req("text")
            val pos = c.bridge.currentPosition()
            val vf = c.str("file")?.let { E2mCommands.resolveFile(project, it) } ?: pos?.file
            val line0 = c.int("line")?.let { it - 1 } ?: if (vf != null && pos != null && vf == pos.file) pos.line else null
            val vars = if (c.bool("captureVariables", true) && pos != null) runCatching { c.bridge.variables(0, 0) }.getOrNull() else null
            val step = E2mStep(
                title = c.str("title"), text = text,
                file = vf?.let { E2mPaths.relative(project, it) }, line = (line0 ?: -1) + 1,
                anchor = if (vf != null && line0 != null) c.bridge.lineText(vf, line0) else null,
                variables = vars,
            )
            val index = c.recorder.addStep(step)
            val total = c.recorder.session?.steps()?.size ?: 0
            val evals = step.evaluations.orEmpty().map { it.expression to (it.value ?: JsonPrimitive("error: ${it.error}")) }
            val view = StepView("ai", index, total, step.title, text, step.file?.let { "$it:${step.line}" }, E2mPresenter.describe(vars, evals))
            edt { E2mPresenter.getInstance(project).show(view, vf, line0, c.bool("inline", true)) }
            json { addProperty("shown", true); addProperty("recordedStep", if (index >= 0) index + 1 else null) }
        },
        Tool(
            "e2m_session_start", "Start recording a replayable session into .e2m/sessions/<name>.json.",
            schema(
                "name" to prop("string", "File name, e.g. 'linear-regression' (letters, digits, . _ -)"),
                "title" to prop("string", "Human title"), "description" to prop("string", "What this walkthrough explains"),
                "runConfiguration" to prop("string", "Run configuration for live debug (default: the one passed to e2m_start_debug)"),
                required = listOf("name"),
            ),
        ) { c ->
            val name = c.req("name")
            E2mPaths.sessionPath(c.project, name) // validates
            c.recorder.start(name, c.str("title"), c.str("description"), c.str("runConfiguration"))
            E2mPresenter.getInstance(c.project).status("Recording '$name'")
            json { addProperty("recording", name) }
        },
        Tool(
            "e2m_session_save", "Save the recorded session (and stop recording unless keepRecording=true).",
            schema("keepRecording" to prop("boolean", "Keep recording after saving (default false)")),
        ) { c ->
            val path = c.recorder.save()
            val steps = c.recorder.session?.steps()?.size
            if (!c.bool("keepRecording", false)) c.recorder.stop()
            E2mPresenter.getInstance(c.project).status("Saved session to ${E2mPaths.root(c.project).relativize(path)}")
            json { addProperty("saved", path.toString()); addProperty("steps", steps) }
        },
        Tool("e2m_session_list", "List saved sessions in .e2m/sessions.", schema()) { c ->
            JsonArray().apply {
                E2mSessionStore.list(c.project).forEach { n ->
                    val s = runCatching { E2mSessionStore.load(c.project, n) }.getOrNull()
                    add(json { addProperty("name", n); addProperty("title", s?.title); addProperty("steps", s?.steps()?.size) })
                }
            }
        },
        Tool(
            "e2m_replay", "Open a saved session in the Explain2Me tool window for the user (static, or as a live debug under the debugger).",
            schema("name" to prop("string", "Session name"), "live" to prop("boolean", "Re-run under the debugger (default false)"),
                "step" to prop("integer", "1-based step to show first (static only)"), required = listOf("name")),
        ) { c ->
            val r = E2mReplayer.getInstance(c.project)
            val s = r.load(c.req("name"))
            if (c.bool("live", false)) r.startLive() else r.goTo((c.int("step") ?: 1) - 1)
            replayStatus(c.project, r).apply { addProperty("loaded", s.name) }
        },
        Tool(
            "e2m_replay_control", "Drive the replay loaded with e2m_replay: next / prev / stop, or status.",
            schema("action" to prop("string", "next | prev | stop | status", listOf("next", "prev", "stop", "status")), required = listOf("action")),
        ) { c ->
            val r = E2mReplayer.getInstance(c.project)
            when (c.req("action")) {
                "next" -> r.next()
                "prev" -> r.prev()
                "stop" -> r.stopLive()
                "status" -> {}
                else -> throw E2mException("Unknown action")
            }
            replayStatus(c.project, r)
        },
        Tool(
            "e2m_annotate",
            "Write a permanent e2m explanation comment above a line. With 'id' it writes `e2m :f <id>` and the shadow note " +
                ".e2m/notes/<id>.md; without, an inline `e2m :=` comment. 'endLine' adds a closing `e2m :end` after that line.",
            schema(FILE, LINE, "text" to prop("string", "Explanation (markdown for notes)"),
                "id" to prop("string", "Note id, e.g. 'regression/slope' -> shadow file mode"),
                "title" to prop("string", "Note heading (shadow file mode)"),
                "endLine" to prop("integer", "Last 1-based line of the explained block"),
                required = listOf("file", "line", "text")),
        ) { c -> annotate(c) },
        Tool("e2m_fold", "Collapse or expand all e2m explanation comments in open editors.", schema("expand" to prop("boolean", "true = expand, false = collapse"), required = listOf("expand"))) { c ->
            val expand = c.bool("expand", false)
            val files = JsonArray()
            edt {
                com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.filter { it.project == c.project }.forEach { ed ->
                    val n = E2mFolding.apply(ed, expand)
                    val vf = FileDocumentManager.getInstance().getFile(ed.document)
                    files.add(json {
                        addProperty("file", vf?.let { E2mPaths.relative(c.project, it) })
                        addProperty("markers", dev.e2m.core.E2mMarkers.findAll(ed.document.charsSequence).size)
                        addProperty("foldedRegions", n)
                    })
                }
            }
            json { addProperty("expanded", expand); add("editors", files) }
        },
        Tool("e2m_open", "Open a file at a line in the editor (without narration).", schema(FILE, LINE, required = listOf("file"))) { c ->
            val vf = E2mCommands.resolveFile(c.project, c.req("file"))
            edt { OpenFileDescriptor(c.project, vf, (c.int("line") ?: 1) - 1, 0).navigate(true) }
            json { addProperty("opened", E2mPaths.relative(c.project, vf)) }
        },
    )

    private val byName = tools.associateBy { it.name }

    fun listJson(): JsonArray = JsonArray().apply {
        tools.forEach { t -> add(json { addProperty("name", t.name); addProperty("description", t.description); add("inputSchema", t.schema) }) }
    }

    fun call(name: String, args: JsonObject): JsonElement {
        val tool = byName[name] ?: throw E2mException("Unknown tool '$name'. Known: ${byName.keys.joinToString()}")
        return tool.handler(Ctx(args))
    }

    // ------------------------------------------------------------------ helpers

    /** Waits for the asynchronous replay step to finish, then describes what the user now sees. */
    private fun replayStatus(project: Project, r: E2mReplayer): JsonObject {
        val deadline = System.currentTimeMillis() + 180_000
        Thread.sleep(50)
        while (r.isBusy && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val presenter = E2mPresenter.getInstance(project)
        return json {
            addProperty("session", r.session?.name)
            addProperty("step", r.index + 1)
            addProperty("steps", r.session?.steps()?.size)
            addProperty("live", r.live)
            addProperty("status", presenter.statusLine)
            presenter.current?.let { v -> addProperty("title", v.title); addProperty("location", v.location); addProperty("details", v.details) }
        }
    }

    private fun openProjects() = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }

    private fun resolveProject(args: JsonObject): Project {
        val open = openProjects()
        if (open.isEmpty()) throw E2mException("No project is open in the IDE")
        val wanted = args.get("project")?.takeIf { !it.isJsonNull }?.asString
        if (!wanted.isNullOrBlank()) {
            return open.firstOrNull { it.name == wanted || it.basePath == wanted || wanted.startsWith((it.basePath ?: "\u0000") + "/") }
                ?: throw E2mException("Project '$wanted' is not open. Open: ${open.joinToString { "${it.name} (${it.basePath})" }}")
        }
        // An absolute file path tells us the project too.
        args.get("file")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { Paths.get(it).isAbsolute }?.let { f ->
            open.filter { p -> p.basePath != null && f.startsWith(p.basePath + "/") }.maxByOrNull { it.basePath!!.length }?.let { return it }
        }
        if (open.size == 1) return open.first()
        return IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.takeIf { it in open } ?: open.first()
    }

    private fun edt(action: () -> Unit) {
        var error: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait({ try { action() } catch (t: Throwable) { error = t } }, ModalityState.nonModal())
        error?.let { throw E2mException(it.message ?: it.toString()) }
    }

    private fun annotate(c: Ctx): JsonElement {
        val project = c.project
        val vf = E2mCommands.resolveFile(project, c.req("file"))
        val line0 = (c.int("line") ?: throw E2mException("'line' is required")) - 1
        val endLine0 = c.int("endLine")?.minus(1)
        val text = c.req("text").trim()
        val id = c.str("id")
        val rel = E2mPaths.relative(project, vf)

        if (id != null) {
            val notePath = E2mPaths.notePath(project, id)
            val heading = c.str("title")?.let { "# $it\n\n" } ?: ""
            if (Files.exists(notePath)) Files.writeString(notePath, Files.readString(notePath).trimEnd() + "\n\n" + text + "\n")
            else E2mPaths.write(notePath, heading + text + "\n")
            E2mPaths.findVirtual(notePath)
        }

        var inserted = 0
        edt {
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: throw E2mException("Cannot open ${vf.name} as text")
            if (line0 !in 0 until doc.lineCount) throw E2mException("Line ${line0 + 1} is outside ${vf.name}")
            val psi = PsiManager.getInstance(project).findFile(vf)
            val commenter = psi?.let { LanguageCommenters.INSTANCE.forLanguage(it.language) }
            val linePrefix = commenter?.lineCommentPrefix?.trimEnd() ?: "//"
            val blockPrefix = commenter?.blockCommentPrefix
            val blockSuffix = commenter?.blockCommentSuffix

            fun indentOf(l: Int): String {
                val s = doc.charsSequence.subSequence(doc.getLineStartOffset(l), doc.getLineEndOffset(l))
                return s.takeWhile { it == ' ' || it == '\t' }.toString()
            }
            val indent = indentOf(line0)
            val markerLines: List<String> = when {
                id != null -> listOf("$indent$linePrefix e2m :f $id")
                !text.contains('\n') -> listOf("$indent$linePrefix e2m := $text")
                blockPrefix != null && blockSuffix != null && !text.contains(blockSuffix) -> {
                    val ls = text.lines()
                    listOf("$indent$blockPrefix e2m := ${ls.first()}") +
                        ls.drop(1).dropLast(1).map { "$indent *  $it" } +
                        listOf("$indent *  ${ls.last()} $blockSuffix")
                }
                else -> {
                    val ls = text.lines()
                    listOf("$indent$linePrefix e2m := ${ls.first()}") + ls.drop(1).map { "$indent$linePrefix        $it" }
                }
            }
            WriteCommandAction.runWriteCommandAction(project, "Add e2m Explanation", null, {
                if (endLine0 != null && endLine0 in line0 until doc.lineCount) {
                    val endIndent = indent
                    doc.insertString(doc.getLineEndOffset(endLine0), "\n$endIndent$linePrefix e2m :end")
                }
                doc.insertString(doc.getLineStartOffset(line0), markerLines.joinToString("\n", postfix = "\n"))
            })
            inserted = markerLines.size
            FileDocumentManager.getInstance().saveDocument(doc)
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, line0, 0), false)
        }
        // Keep the line numbers of the session being recorded in sync with the edit.
        if (endLine0 != null) c.recorder.shiftLines(rel, endLine0 + 2, 1)
        c.recorder.shiftLines(rel, line0 + 1, inserted)
        return json {
            addProperty("file", rel)
            addProperty("insertedLines", inserted + if (endLine0 != null) 1 else 0)
            addProperty("note", id?.let { E2mPaths.root(project).relativize(E2mPaths.notePath(project, id)).toString() })
            addProperty("hint", "Lines at or below ${line0 + 1} moved down by $inserted; re-read the file before using line numbers again.")
        }
    }
}

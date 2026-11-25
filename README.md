# Explain2Me (JetBrains plugin)

Explain2Me connects an AI assistant (Claude Code) to the JetBrains IDE debugger so it can **explain code by running it**.
It sets breakpoints, steps through code, reads variables and evaluates expressions. While you watch, it highlights the
current line and draws its narration right above it in the editor.

Each walkthrough can be recorded into the project's `.e2m/` folder and **replayed** later. A replay can be static (no
debugger needed) or live (the recorded run is executed again under the debugger, with values re-evaluated).

Explanations can also live permanently in the code as `e2m` comments. These fold and unfold separately from other comments.

## Architecture: why an HTTP / MCP server inside the IDE?

```
 Claude Code ──MCP (Streamable HTTP, JSON-RPC)──▶ ┌───────────── IDE process ─────────────┐
 curl / scripts ──── POST /api/tools/<name> ────▶ │ E2mServer (127.0.0.1:63390)           │
                                                  │   └─ E2mTools ─▶ DebugBridge (XDebugger API)
                                                  │              ├─▶ E2mRecorder ─▶ .e2m/sessions/*.json
                                                  │              └─▶ E2mPresenter ─▶ editor inlay + tool window
                                                  └───────────────────────────────────────┘
```

* **The debugger lives in the IDE process.** Breakpoints, stepping and evaluation go through the platform's
  `XDebugger` API, which is only reachable from inside the IDE. So the plugin has to be the server, and the AI has to be the client.
* **HTTP is the simplest transport any client can use.** The plugin starts a tiny `com.sun.net.httpserver` server.
  It needs no extra dependencies and doesn't depend on the IDE's internal built-in server, whose API changes between versions.
* **MCP is what Claude speaks.** `POST /mcp` implements the MCP *Streamable HTTP* transport (JSON-RPC 2.0 with JSON
  responses). Claude Code discovers the tools and their schemas on its own. You don't need a wrapper script or a separate MCP process.
  The same tools are also exposed as plain REST (`/api/tools/<name>`) for curl and scripting.
* **Language agnostic.** Because it uses `XDebugger` rather than the Java debugger directly, the same tools work for any
  debugger that plugs into the platform: Java/Kotlin (IDEA), Go (GoLand), Python (PyCharm), JS, PHP, and so on.
* **Safe by default.** The server binds to loopback only. It rejects requests whose `Host` isn't loopback (DNS rebinding)
  and requests carrying a non-local `Origin` (drive-by requests from web pages).
* **Synchronous tools.** Every stepping tool waits until the debugger pauses again, then returns the new position, the
  source line and the variables. One call is one "move" for the AI, which keeps its reasoning simple.

## Install & connect

```bash
./gradlew buildPlugin            # -> build/distributions/explain2me-jetbrains-0.1.0.zip
```

In the IDE, go to *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick the zip. It works with 2025.2+ IDEs,
for example IntelliJ IDEA 2026.1 or GoLand 2025.2. Then connect Claude Code:

```bash
claude mcp add --transport http explain2me http://127.0.0.1:63390/mcp
```

The IDE shows the exact URL in a notification and in the **Explain2Me** tool window. If port 63390 is busy, the next free
port up to 63399 is used. You can force a port with `-De2m.port=…` in the IDE's VM options.

Then ask Claude, for example: *"Use explain2me to walk me through how `LinearRegression.fit` computes the slope, and record it as a session."*

To try it without installing, run `./gradlew runIde`. This opens a sandbox IDE with the plugin and the bundled Java example (`examples/e2m_example_java_linear_regression/`).

## Tools

| Tool | What it does |
|---|---|
| `e2m_status` | Open projects, debugger state, breakpoints, recording, saved sessions |
| `e2m_list_run_configurations` | Run configurations that can be debugged |
| `e2m_start_debug` | Launch a configuration under the debugger and wait for the first pause |
| `e2m_set_breakpoint` / `e2m_remove_breakpoint` / `e2m_list_breakpoints` | Line breakpoints (optionally conditional) |
| `e2m_step` | `over`, `into`, `out`, `resume` or `pause`, then return the new state |
| `e2m_run_to_line` | Resume until a line is reached |
| `e2m_get_state` / `e2m_get_frames` / `e2m_get_variables` / `e2m_evaluate` | Inspect the paused program |
| `e2m_explain` | Highlight a line and show the narration in the editor and tool window. When recording, it becomes a replay step |
| `e2m_session_start` / `e2m_session_save` / `e2m_session_list` | Record walkthroughs into `.e2m/sessions/` |
| `e2m_replay` / `e2m_replay_control` | Present a saved session (static, or as a live debug) and drive it with `next`/`prev`/`stop` |
| `e2m_annotate` | Write a permanent `e2m` comment (inline, or with a shadow note) into the code |
| `e2m_fold` | Collapse or expand all e2m comments in open editors |
| `e2m_stop_debug`, `e2m_open` | Utility |

All line numbers are 1-based. Paths can be absolute or relative to the project root. With several projects open, pass
`project`, or use an absolute file path and the plugin picks the matching project.

## e2m comments in code

```java
// e2m :f regression/slope          ← explanation in the shadow file .e2m/notes/regression/slope.md
double sxy = Stats.sumOfCrossProducts(xs, xMean, ys, yMean);
double slope = sxy / sxx;
// e2m :end                         ← optional: closes the block the marker explains

/* e2m := Hand-written, verbose,
 *  multi-line explanation that lives right here. */

// e2m := Single line explanation,
//        continued on the following line comments.
```

* Any comment syntax works (`//`, `#`, `/* */`, `--`, `;`, `<!-- -->`), so this works in every language.
* **Fold separately from other comments:** *Code → Explain2Me → Collapse / Expand / Toggle e2m Explanations*
  (Toggle is <kbd>Ctrl+Alt+Shift+E</kbd>). Other folds aren't touched. The tool window has the same buttons, plus
  *Fold e2m comments by default*. Collapsed markers show `ⓔ regression/slope — Step 2 – the slope`.
* <kbd>Ctrl/Cmd</kbd>+click on `e2m :f some/id` opens the shadow note.
* *Show e2m Explanation at Caret* opens the note, or shows the inline text, that covers the current line.
* *Add e2m Explanation…* inserts a marker above the caret line and creates the note.

## The `.e2m` folder

```
.e2m/
  notes/<id>.md            shadow files for `e2m :f <id>`
  sessions/<name>.json     recorded walkthroughs
```

A session is a list of **steps**. Each step has a location (`file`, 1-based `line`, plus `anchor`, the source text of
that line, so the step can be found again if the code moves), the narration `text` (markdown), the recorded
`variables` and `evaluations`, and the **debugger `commands`** that led there since the previous step
(`set_breakpoint`, `start`, `over`, `into`, `out`, `resume`, `run_to_line`, …).

* **Static replay** (tool window *Next ▶* / *◀ Prev*, or clicking a step) shows each step with the values recorded at the time.
* **Live debug** (the button with the debug icon) starts the session's run configuration under the debugger and re-executes each
  step's commands, so the program really stops at the same places. Values are re-evaluated live. Other breakpoints
  are temporarily disabled and restored afterwards.

The files are plain JSON and markdown, so they can be reviewed, committed and shared like code.

## Stepping through a session

Open the **Explain2Me** tool window (right-hand side; the ⓔ icon), pick a session in the *Session* box and press **Load**.
The step list fills in and step 1 is shown.

Explain2Me never starts your program by itself. Opening the project, loading a session and static replay only read files.
The program is launched only when you press **Live debug**, or when Claude calls `e2m_start_debug` (or `e2m_replay` with
`live: true`). It is always launched with the **Debug** executor, never plain Run.

**Static replay**, for reading. No debugger is needed:
* **Next ▶** / **◀ Prev** move one step. Clicking a step in the list jumps straight to it.
* For each step, the editor opens the file, highlights the line in the debugger's execution-point colour and draws the
  narration in a box just above it. The tool window shows the same text, plus the variables and evaluated expressions
  that were recorded at that point.
* The e2m comments in the shown file are folded so the narration has room. Use *Expand e2m* (or
  <kbd>Ctrl+Alt+Shift+E</kbd>) to open them again.

**Live debug**, which runs the program for real:
1. Press **Live debug**. The plugin temporarily disables your own breakpoints, sets the session's breakpoints and starts the
   session's run configuration (*Regression demo* in the examples) under the debugger.
2. The program stops at step 1. The **Debug** tool window opens as usual, with the normal blue execution line, and the
   frames and variables panes are live.
3. Press **Next ▶** to run the recorded debugger commands for the next step (step into, step over, resume to the next
   breakpoint, …). The program really moves forward, and the values shown are evaluated again in the running program.
4. You can poke around between steps, for example by hovering variables or evaluating expressions. Stepping by hand moves
   the program away from where the session expects it, though, so let **Next ▶** drive.
5. Live debug only goes forward. After the last step, or when you press **Stop**, the debugger is stopped, the
   session's breakpoints are removed and your own breakpoints are re-enabled. To look back, use static replay.

**Letting Claude drive** works the same way over MCP. Ask Claude to "run the linear-regression session as a live debug and explain each
step", and it calls `e2m_replay` / `e2m_replay_control next`. It can also step the debugger itself (`e2m_step`, `e2m_run_to_line`,
`e2m_evaluate`) and narrate with `e2m_explain`, which draws in the editor exactly like a replay step.

## Examples (`examples/`)

The same least-squares line fit is provided in three languages. Each one fits a line to the data (1,2) (2,4) (3,5) (4,4) (5,5):

| Folder | IDE | Files |
|---|---|---|
| `e2m_example_java_linear_regression/` | IntelliJ IDEA | `Main.java`, `LinearRegression.java`, `Stats.java` |
| `e2m_example_go_linear_regression/` | GoLand | `main.go`, `regression.go`, `stats.go` |
| `e2m_example_python_linear_regression/` | PyCharm (or IDEA with the Python plugin) | `main.py`, `linear_regression.py`, `stats.py` |

* The entry file holds the data, prints the result and makes a prediction.
* The regression file follows the recipe: means → slope b = Sxy/Sxx → intercept a = ȳ − b·x̄ → R².
* The stats file has the building blocks: the mean, the sum of squares (Sxx) and the sum of cross products (Sxy).

The result is **y = 2.2 + 0.6·x** with **R² = 0.6**. Each example contains:
* **Comments:** both e2m comment styles. The Python one uses `#` line comments, since Python has no block comments.
* **Notes:** shadow notes in `.e2m/notes/`.
* **Session:** a 10-step walkthrough in `.e2m/sessions/linear-regression.json`.
* **Run configuration:** a *Regression demo* configuration in `.run/`.

The Java and Go sessions were recorded by the plugin itself, driving the real debugger (the Java debugger and Delve).

To try one, open its folder in the matching IDE:
* **Java:** pick a JDK 17+ as the project SDK if asked.
* **Go:** a Go SDK is needed.
* **Python:** pick a Python 3 interpreter.

Then open the **Explain2Me** tool window, choose `linear-regression`, and press **Load** and **Next ▶**, or **Live debug**
(see [Stepping through a session](#stepping-through-a-session)).

## Development

* Kotlin, IntelliJ Platform Gradle Plugin 2.x. By default it compiles against your local IDE (`localIde` in
  `gradle.properties`). Set `platformVersion=2025.2` to use a downloaded IDE instead.
* `./gradlew runIde` opens a sandbox IDE with the Java example. Use `-PrunExample=examples/<folder>` to open another
  example, and `-PlocalIde=/Applications/GoLand.app` to run in a different local IDE. `./gradlew buildPlugin` builds the installable zip.
* **Debugging the plugin:** the sandbox writes its log to
  `.intellijPlatform/sandbox/explain2me-jetbrains/<IDE>-<version>/log_runIde/idea.log`. The red IDE-error indicator
  corresponds to `SEVERE` entries in that log, and each entry names the plugin to blame. The plugin logs as `#dev.e2m.*`.
  In an installed IDE, use *Help → Show Log in Finder/Explorer*.
* Main sources (`src/main/kotlin/dev/e2m/`):
  * `server/`: the HTTP/MCP server and the tool catalogue
  * `debug/`: the XDebugger facade and the command executor
  * `session/`: the session model, recorder and store
  * `core/`: marker parsing, folding and notes
  * `ui/`: the tool window, editor inlays and replay

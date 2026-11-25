package dev.e2m.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.e2m.core.E2mFolding
import dev.e2m.core.E2mSettings
import dev.e2m.server.E2mServer
import dev.e2m.session.E2mRecorder
import dev.e2m.session.E2mSessionStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

class E2mToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = E2mPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class E2mPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val replayer = E2mReplayer.getInstance(project)
    private val presenter = E2mPresenter.getInstance(project)

    private val serverLabel = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val statusLabel = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val sessions = ComboBox<String>()
    private val stepsModel = DefaultListModel<String>()
    private val steps = JBList(stepsModel)
    private val title = JBLabel().apply { font = font.deriveFont(Font.BOLD, font.size2D + 1) }
    private val locationLabel = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL; foreground = UIUtil.getContextHelpForeground() }
    private val narration = JEditorPane("text/html", "").apply { isEditable = false; border = JBUI.Borders.empty(4) }
    private val details = JTextArea().apply {
        isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size - 1); border = JBUI.Borders.empty(4)
    }
    private val prev = JButton("◀ Prev")
    private val next = JButton("Next ▶")
    private val liveBtn = JButton("Live debug", AllIcons.Actions.StartDebugger).apply {
        toolTipText = "Run the session under the debugger, stopping at each step"
    }
    private val stopLive = JButton("Stop")
    private var updatingSteps = false

    init {
        val load = JButton("Load").apply { addActionListener { (sessions.selectedItem as? String)?.let { runCatching { replayer.load(it); replayer.goTo(0) }.onFailure { e -> presenter.status("Error: ${e.message}") } } } }
        val refresh = JButton("↻").apply { toolTipText = "Refresh sessions in .e2m/sessions"; addActionListener { refreshSessions() } }
        prev.addActionListener { replayer.prev() }
        next.addActionListener { replayer.next() }
        liveBtn.toolTipText = "Re-run the session under the debugger, re-executing each step's breakpoints and steps"
        liveBtn.addActionListener { replayer.startLive() }
        stopLive.addActionListener { replayer.stopLive() }
        steps.addListSelectionListener { if (!it.valueIsAdjusting && !updatingSteps && !replayer.live && steps.selectedIndex >= 0) replayer.goTo(steps.selectedIndex) }

        val top = Box.createVerticalBox().apply {
            border = JBUI.Borders.empty(6)
            add(row(serverLabel))
            add(row(statusLabel))
            add(row(JBLabel("Session:"), sessions, refresh, load))
            add(row(prev, next, liveBtn, stopLive))
        }

        val stepPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(Box.createVerticalBox().apply { add(row(title)); add(row(locationLabel)) }, BorderLayout.NORTH)
            add(JBSplitter(true, 0.6f).apply {
                firstComponent = JBScrollPane(narration)
                secondComponent = JBScrollPane(details)
            }, BorderLayout.CENTER)
        }

        val collapseDefault = JBCheckBox("Fold e2m comments by default", E2mSettings.getInstance().collapseByDefault).apply {
            addActionListener {
                E2mSettings.getInstance().collapseByDefault = isSelected
                E2mFolding.applyToProject(project, expand = !isSelected)
            }
        }
        val bottom = row(
            collapseDefault,
            JButton("Collapse e2m").apply { addActionListener { E2mFolding.applyToProject(project, false) } },
            JButton("Expand e2m").apply { addActionListener { E2mFolding.applyToProject(project, true) } },
        ).apply { border = JBUI.Borders.empty(2, 6) }

        add(top, BorderLayout.NORTH)
        add(JBSplitter(true, 0.25f).apply {
            firstComponent = JBScrollPane(steps)
            secondComponent = stepPanel
        }, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        presenter.addListener({ refresh() }, this)
        refreshSessions()
        refresh()
    }

    private fun row(vararg c: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
        alignmentX = LEFT_ALIGNMENT
        c.forEach { add(it) }
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun refreshSessions() {
        val selected = sessions.selectedItem
        sessions.removeAllItems()
        runCatching { E2mSessionStore.list(project) }.getOrDefault(emptyList()).forEach { sessions.addItem(it) }
        if (selected != null) sessions.selectedItem = selected
    }

    private fun refresh() {
        val server = E2mServer.getInstance()
        val rec = E2mRecorder.getInstance(project).session
        serverLabel.text = if (server.isRunning) "MCP: ${server.mcpUrl}" else "Server not running"
        statusLabel.text = buildString {
            if (rec != null) append("● Recording '${rec.name}' (${rec.steps().size} steps)   ")
            append(presenter.statusLine)
        }

        val s = replayer.session
        updatingSteps = true
        try {
            val titles = s?.steps()?.mapIndexed { i, st -> "${i + 1}. ${st.title ?: st.text.lineSequence().firstOrNull().orEmpty().take(60)}" }.orEmpty()
            if (titles != (0 until stepsModel.size()).map { stepsModel[it] }) {
                stepsModel.clear(); titles.forEach { stepsModel.addElement(it) }
            }
            steps.selectedIndex = if (s != null && presenter.current?.source != "ai") replayer.index else -1
        } finally {
            updatingSteps = false
        }

        val busy = replayer.isBusy
        prev.isEnabled = !busy && replayer.hasPrev()
        next.isEnabled = !busy && (replayer.hasNext())
        liveBtn.isEnabled = !busy && s != null && !replayer.live
        stopLive.isEnabled = replayer.live

        val v = presenter.current
        if (v == null) {
            title.text = "No explanation yet"
            locationLabel.text = "Ask Claude to explain some code, or load a session from .e2m/sessions."
            narration.text = ""
            details.text = ""
        } else {
            val counter = if (v.index >= 0 && v.total > 0) "[${v.index + 1}/${v.total}] " else ""
            title.text = counter + (v.title ?: "Explanation")
            locationLabel.text = listOfNotNull(v.location, v.source).joinToString("  ·  ")
            narration.text = "<html><body style='font-family: sans-serif'>${MiniMarkdown.toHtml(v.text)}</body></html>"
            narration.caretPosition = 0
            details.text = v.details.orEmpty()
            details.caretPosition = 0
        }
    }

    override fun dispose() {}
}

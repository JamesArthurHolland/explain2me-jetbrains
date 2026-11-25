package dev.e2m.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.e2m.debug.DebugBridge
import dev.e2m.server.E2mServer
import java.util.concurrent.atomic.AtomicBoolean

class E2mStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        DebugBridge.getInstance(project) // subscribe to debugger events early
        E2mFoldingRegistrar.ensureRegistered()
        E2mFoldingRegistrar.refreshEditors(project)
        val server = E2mServer.getInstance()
        val error = runCatching { server.ensureStarted() }.exceptionOrNull()
        if (notified.compareAndSet(false, true)) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Explain2Me")
            if (error != null) {
                group.createNotification("Explain2Me server failed to start: ${error.message}", NotificationType.ERROR).notify(project)
            } else {
                group.createNotification(
                    "Explain2Me listening on ${server.mcpUrl}",
                    "Connect Claude Code with:<br/><code>claude mcp add --transport http explain2me ${server.mcpUrl}</code>",
                    NotificationType.INFORMATION,
                ).notify(project)
            }
        }
    }

    companion object {
        private val notified = AtomicBoolean(false)
    }
}

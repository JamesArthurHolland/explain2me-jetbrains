package dev.e2m.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Application-wide Explain2Me settings. */
@Service(Service.Level.APP)
@State(name = "Explain2MeSettings", storages = [Storage("explain2me.xml")])
class E2mSettings : PersistentStateComponent<E2mSettings.State> {
    class State {
        /** Preferred port for the local server. The next free port is used if it is taken. */
        var port: Int = DEFAULT_PORT
        /** Whether e2m explanation comments start folded when a file is opened. */
        var collapseByDefault: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    val port: Int get() = Integer.getInteger("e2m.port") ?: state.port

    var collapseByDefault: Boolean
        get() = state.collapseByDefault
        set(value) {
            state.collapseByDefault = value
        }

    companion object {
        const val DEFAULT_PORT = 63390
        fun getInstance(): E2mSettings = service()
    }
}

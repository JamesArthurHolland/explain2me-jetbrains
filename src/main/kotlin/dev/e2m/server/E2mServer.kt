package dev.e2m.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.e2m.core.E2mSettings
import dev.e2m.debug.E2mException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local HTTP server (bound to 127.0.0.1 only) that exposes the Explain2Me tools two ways:
 *
 *  - `POST /mcp`          MCP "Streamable HTTP" endpoint (JSON-RPC 2.0, JSON responses), for Claude Code:
 *                         `claude mcp add --transport http explain2me http://127.0.0.1:63390/mcp`
 *  - `GET  /api/tools`    list of tools with JSON schemas
 *    `POST /api/tools/<name>` call a tool with a JSON object body (handy for curl / scripts)
 *    `GET  /api/status`   same as the e2m_status tool
 *
 * One server per IDE instance; tools pick the project with the optional `project` argument.
 */
@Service(Service.Level.APP)
class E2mServer : Disposable {
    private val log = logger<E2mServer>()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    @Volatile
    var port: Int = -1
        private set

    val isRunning get() = server != null
    val mcpUrl get() = "http://127.0.0.1:$port/mcp"

    @Synchronized
    fun ensureStarted() {
        if (server != null) return
        val preferred = E2mSettings.getInstance().port
        var lastError: Exception? = null
        for (candidate in preferred until preferred + 10) {
            try {
                val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0)
                val ex = Executors.newCachedThreadPool { r -> Thread(r, "Explain2Me-http").apply { isDaemon = true } }
                s.executor = ex
                s.createContext("/") { handle(it) }
                s.start()
                server = s; executor = ex; port = candidate
                log.info("Explain2Me server listening on $mcpUrl")
                return
            } catch (e: BindException) {
                lastError = e
            }
        }
        throw IllegalStateException("No free port in $preferred..${preferred + 9}", lastError)
    }

    private fun handle(ex: HttpExchange) {
        try {
            if (!isTrustedRequest(ex)) return respond(ex, 403, error("Forbidden: only local, non-browser clients are allowed"))
            val path = ex.requestURI.path.trimEnd('/')
            when {
                path == "/mcp" -> handleMcp(ex)
                path == "/api/status" -> respond(ex, 200, callTool("e2m_status", JsonObject()))
                path == "/api/tools" && ex.requestMethod == "GET" -> respond(ex, 200, E2mTools.listJson())
                path.startsWith("/api/tools/") && ex.requestMethod == "POST" -> {
                    val name = path.removePrefix("/api/tools/")
                    val args = readJson(ex)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    try {
                        respond(ex, 200, callTool(name, args))
                    } catch (e: Exception) {
                        respond(ex, 400, error(e.message ?: e.toString()))
                    }
                }
                path == "" -> respond(ex, 200, JsonObject().apply {
                    addProperty("name", "explain2me"); addProperty("mcp", mcpUrl); addProperty("tools", "/api/tools")
                })
                else -> respond(ex, 404, error("Not found"))
            }
        } catch (t: Throwable) {
            log.warn("Explain2Me request failed", t)
            runCatching { respond(ex, 500, error(t.message ?: t.toString())) }
        } finally {
            ex.close()
        }
    }

    /** Blocks DNS-rebinding and drive-by requests from web pages: Host must be loopback, Origin absent or loopback. */
    private fun isTrustedRequest(ex: HttpExchange): Boolean {
        val host = ex.requestHeaders.getFirst("Host")?.substringBeforeLast(':')?.trim('[', ']') ?: return false
        if (host !in setOf("127.0.0.1", "localhost", "::1")) return false
        val origin = ex.requestHeaders.getFirst("Origin") ?: return true
        return Regex("""^https?://(127\.0\.0\.1|localhost|\[::1])(:\d+)?$""").matches(origin)
    }

    // ------------------------------------------------------------------ MCP (JSON-RPC 2.0)

    private fun handleMcp(ex: HttpExchange) {
        when (ex.requestMethod) {
            "POST" -> {}
            "DELETE" -> return respondEmpty(ex, 200)
            // We do not offer a server->client SSE stream.
            else -> return respondEmpty(ex, 405)
        }
        val body = readJson(ex) ?: return respond(ex, 400, rpcError(JsonNull.INSTANCE, -32700, "Parse error"))
        if (body.isJsonArray) {
            val responses = JsonArray()
            body.asJsonArray.forEach { msg -> rpc(msg.asJsonObject)?.let { responses.add(it) } }
            return if (responses.isEmpty) respondEmpty(ex, 202) else respond(ex, 200, responses)
        }
        val response = rpc(body.asJsonObject) ?: return respondEmpty(ex, 202)
        respond(ex, 200, response)
    }

    /** Handles one JSON-RPC message; returns null for notifications. */
    private fun rpc(msg: JsonObject): JsonObject? {
        val id = msg.get("id")
        val method = msg.get("method")?.asString ?: return null // a response from the client: ignore
        if (id == null || id.isJsonNull) return null // notification (e.g. notifications/initialized)
        val params = msg.getAsJsonObject("params") ?: JsonObject()
        return try {
            val result: JsonElement = when (method) {
                "initialize" -> JsonObject().apply {
                    addProperty("protocolVersion", params.get("protocolVersion")?.asString ?: "2025-06-18")
                    add("capabilities", JsonObject().apply { add("tools", JsonObject().apply { addProperty("listChanged", false) }) })
                    add("serverInfo", JsonObject().apply { addProperty("name", "explain2me"); addProperty("version", "0.1.0") })
                    addProperty("instructions", E2mTools.INSTRUCTIONS)
                }
                "ping" -> JsonObject()
                "tools/list" -> JsonObject().apply { add("tools", E2mTools.listJson()) }
                "tools/call" -> {
                    val name = params.get("name")?.asString ?: throw IllegalArgumentException("Missing tool name")
                    val args = params.getAsJsonObject("arguments") ?: JsonObject()
                    try {
                        toolResult(E2mTools.gson.toJson(callTool(name, args)), false)
                    } catch (e: E2mException) {
                        toolResult(e.message ?: "error", true)
                    } catch (e: IllegalArgumentException) {
                        toolResult(e.message ?: "error", true)
                    } catch (e: IllegalStateException) {
                        toolResult(e.message ?: "error", true)
                    }
                }
                "resources/list" -> JsonObject().apply { add("resources", JsonArray()) }
                "prompts/list" -> JsonObject().apply { add("prompts", JsonArray()) }
                else -> return rpcError(id, -32601, "Method not found: $method")
            }
            JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id); add("result", result) }
        } catch (e: Exception) {
            log.warn("MCP $method failed", e)
            rpcError(id, -32603, e.message ?: e.toString())
        }
    }

    private fun toolResult(text: String, isError: Boolean) = JsonObject().apply {
        add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }) })
        addProperty("isError", isError)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0"); add("id", id)
        add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
    }

    private fun callTool(name: String, args: JsonObject): JsonElement = E2mTools.call(name, args)

    // ------------------------------------------------------------------ plumbing

    private fun readJson(ex: HttpExchange): JsonElement? {
        val text = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()
    }

    private fun error(message: String) = JsonObject().apply { addProperty("error", message) }

    private fun respond(ex: HttpExchange, code: Int, body: JsonElement) {
        val bytes = E2mTools.gson.toJson(body).toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondEmpty(ex: HttpExchange, code: Int) {
        ex.sendResponseHeaders(code, -1)
    }

    @Synchronized
    override fun dispose() {
        server?.stop(0); server = null
        executor?.shutdownNow(); executor = null
    }

    companion object {
        fun getInstance(): E2mServer = service()
    }
}

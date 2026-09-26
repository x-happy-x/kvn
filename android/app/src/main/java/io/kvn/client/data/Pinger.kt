package io.kvn.client.data

import io.kvn.client.core.CoreBridge
import io.kvn.client.core.Engine

/** Пинг сервера способом из настроек. */
object Pinger {
    /**
     * Задержка в мс; -1 — не ответил; 0 — этим способом не измерить (у сервера
     * нет адреса, например olcrtc, а способ не через прокси).
     */
    fun ping(node: ServerNode, engine: Engine, settings: AppSettings, method: PingMethod = settings.checks.pingMethod): Int {
        val checks = settings.checks
        if (!method.throughProxy && (node.server.isEmpty() || node.port <= 0)) return 0
        if (method.throughProxy && !node.supports(engine)) return -1
        return runCatching {
            CoreBridge.ping(method.id, engine, node.json, settings.coreOptions(1500), checks.testUrl, checks.pingTimeoutMs)
        }.getOrDefault(-1).let { if (it == 0) 1 else it }
    }
}

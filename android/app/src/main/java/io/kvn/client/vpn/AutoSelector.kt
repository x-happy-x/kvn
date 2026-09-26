package io.kvn.client.vpn

import android.util.Log
import io.kvn.client.core.CoreBridge
import io.kvn.client.data.PingMethod
import io.kvn.client.data.PingStats
import io.kvn.client.data.Pinger
import io.kvn.client.data.Repository
import io.kvn.client.data.ServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Авто-выбор сервера: кандидаты сортируются по статистике (как часто
 * работают и как быстро), самые многообещающие быстро пингуются, а затем по
 * очереди проверяются настоящим запросом через сам сервер — берётся первый,
 * через который страница открылась.
 */
class AutoSelector(private val repository: Repository, private val stats: PingStats) {

    /**
     * Лучший работающий сервер или null. [exclude] — id серверов, которые
     * пропускаем (например, только что отказавший). [around] — сервер, из
     * подписки которого выбирать, когда поиск ограничен одной подпиской.
     */
    suspend fun pickBest(exclude: Set<String> = emptySet(), around: ServerNode? = null): ServerNode? = withContext(Dispatchers.IO) {
        val settings = repository.settings.value
        val candidates = repository.allNodes
            .filter { it.id !in exclude }
            .filter { settings.auto.allSubscriptions || around == null || it.subscriptionId == around.subscriptionId }
            .filter { it.supports(repository.engineFor(it)) }
        if (candidates.isEmpty()) return@withContext null

        // Быстрый пинг первых по статистике: отсекает явно лежащие. Способы
        // «через прокси» здесь заменяются TCP — настоящая проверка идёт следом.
        val shortlist = stats.order(candidates).take(PING_SHORTLIST)
        val quickMethod = if (settings.checks.pingMethod == PingMethod.ICMP) PingMethod.ICMP else PingMethod.TCP
        val pinged = coroutineScope {
            shortlist.map { node ->
                async {
                    val ms = Pinger.ping(node, repository.engineFor(node), settings, quickMethod)
                    if (node.server.isNotEmpty()) stats.record(node, ms > 0, ms)
                    node to ms
                }
            }.awaitAll()
        }
        val alive = pinged
            .filter { (_, ms) -> ms >= 0 }
            .sortedByDescending { (node, ms) ->
                val score = stats.get(node)?.score ?: 0.9
                // Текущий пинг поправляет историю: быстрый сейчас — чуть выше.
                if (ms > 0) score * 1000.0 / (ms + 400.0) else score
            }
            .map { it.first }

        val options = settings.coreOptions(1500)
        for (node in alive.take(TEST_LIMIT)) {
            // Сервер рабочий, если открылся хотя бы один адрес проверки.
            var result: JSONObject? = null
            for (url in settings.checks.testUrls.ifEmpty { listOf("") }) {
                result = runCatching {
                    JSONObject(CoreBridge.testNode(repository.engineFor(node), node.json, options, url, settings.checks.testTimeoutMs.coerceAtMost(10_000)))
                }.getOrNull()
                if (result?.optBoolean("ok") == true || result?.optString("verdict") == "down") break
            }
            val ok = result?.optBoolean("ok") == true
            stats.record(node, ok, result?.optInt("ms") ?: 0)
            Log.i(TAG, "auto: ${node.name} → ${if (ok) "ok ${result?.optInt("ms")} ms" else result?.optString("error")}")
            if (ok) {
                stats.save()
                return@withContext node
            }
        }
        stats.save()
        null
    }

    companion object {
        private const val TAG = "KvnAuto"
        private const val PING_SHORTLIST = 12
        private const val TEST_LIMIT = 5
    }
}

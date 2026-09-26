package io.kvn.client.data

/** Сервер с его историей проверок. */
data class ServerStat(val node: ServerNode, val subscription: String, val record: PingRecord)

/** Сводка по подписке: сколько её серверов работает по истории проверок. */
data class SubscriptionStat(
    val subscription: Subscription,
    val total: Int,
    val checked: Int,
    val working: Int,
    /** Средняя надёжность проверенных серверов, 0..1. */
    val reliability: Double,
    val avgMs: Int,
    val best: ServerStat?,
)

/**
 * Статистика для экрана «Проверка → Статистика»: собирается из истории
 * пингов и проверок ([PingStats]) и текущего списка серверов.
 */
data class StatsReport(
    val totalServers: Int,
    val checkedServers: Int,
    val workingServers: Int,
    val totalChecks: Int,
    val reliability: Double,
    val avgMs: Int,
    val reliable: List<ServerStat>,
    val problematic: List<ServerStat>,
    val subscriptions: List<SubscriptionStat>,
) {
    val empty: Boolean get() = checkedServers == 0

    companion object {
        /** Сервер «работает», если по истории больше половины проверок успешны. */
        const val WORKING_RATE = 0.5

        fun build(subscriptions: List<Subscription>, nodesOf: (Subscription) -> List<ServerNode>, records: Map<String, PingRecord>): StatsReport {
            val perSubscription = subscriptions.filter { it.enabled }.map { subscription ->
                subscription to nodesOf(subscription).map { node ->
                    ServerStat(node, subscription.name, records[PingStats.keyOf(node)] ?: PingRecord())
                }
            }
            val all = perSubscription.flatMap { it.second }
            val checked = all.filter { it.record.checks > 0 }
            val working = checked.filter { it.record.successRate > WORKING_RATE }
            val latencies = checked.mapNotNull { stat -> stat.record.avgMs.takeIf { it > 0 } }
            return StatsReport(
                totalServers = all.size,
                checkedServers = checked.size,
                workingServers = working.size,
                totalChecks = checked.sumOf { it.record.checks },
                reliability = if (checked.isEmpty()) 0.0 else checked.map { it.record.successRate }.average(),
                avgMs = if (latencies.isEmpty()) 0 else latencies.average().toInt(),
                reliable = working.sortedByDescending { it.record.score }.take(8),
                problematic = checked
                    .filter { it.record.checks >= 3 && it.record.successRate <= WORKING_RATE }
                    .sortedBy { it.record.successRate }
                    .take(6),
                subscriptions = perSubscription.map { (subscription, stats) ->
                    val subChecked = stats.filter { it.record.checks > 0 }
                    val subWorking = subChecked.filter { it.record.successRate > WORKING_RATE }
                    val subLatency = subChecked.mapNotNull { stat -> stat.record.avgMs.takeIf { it > 0 } }
                    SubscriptionStat(
                        subscription = subscription,
                        total = stats.size,
                        checked = subChecked.size,
                        working = subWorking.size,
                        reliability = if (subChecked.isEmpty()) 0.0 else subChecked.map { it.record.successRate }.average(),
                        avgMs = if (subLatency.isEmpty()) 0 else subLatency.average().toInt(),
                        best = subWorking.maxByOrNull { it.record.score },
                    )
                }.sortedWith(compareByDescending<SubscriptionStat> { it.checked > 0 }.thenByDescending { it.reliability }),
            )
        }
    }
}

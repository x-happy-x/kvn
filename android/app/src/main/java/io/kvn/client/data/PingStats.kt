package io.kvn.client.data

import android.content.Context
import org.json.JSONObject

/**
 * История проверок одного сервера. [ok] и [fail] — затухающие счётчики: старые
 * результаты весят меньше новых, поэтому сервер, который «ожил», быстро
 * поднимается вверх.
 */
data class PingRecord(
    val ok: Double = 0.0,
    val fail: Double = 0.0,
    /** Скользящее среднее задержки успешных проверок, мс. */
    val avgMs: Double = 0.0,
    val lastMs: Int = 0,
    val lastOkAt: Long = 0,
    val checks: Int = 0,
) {
    /** Доля успешных проверок с поправкой на малую выборку (Лаплас). */
    val successRate: Double get() = (ok + 1) / (ok + fail + 2)

    /**
     * Чем больше, тем раньше сервер пингуется и тем выше он в выборе самого
     * быстрого: надёжность важнее скорости, задержка — второй множитель.
     */
    val score: Double get() {
        val latency = if (avgMs > 0) avgMs else 800.0
        return successRate * 1000.0 / (latency + 100.0)
    }

    fun record(success: Boolean, ms: Int, now: Long = System.currentTimeMillis()): PingRecord {
        val ok = this.ok * DECAY + if (success) 1.0 else 0.0
        val fail = this.fail * DECAY + if (success) 0.0 else 1.0
        val avg = when {
            !success -> avgMs
            avgMs <= 0 -> ms.toDouble()
            else -> avgMs * 0.7 + ms * 0.3
        }
        return copy(
            ok = ok,
            fail = fail,
            avgMs = avg,
            lastMs = if (success) ms else -1,
            lastOkAt = if (success) now else lastOkAt,
            checks = checks + 1,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("fail", fail)
        .put("avg", avgMs)
        .put("last", lastMs)
        .put("lastOk", lastOkAt)
        .put("n", checks)

    companion object {
        /** Каждая новая проверка «старит» прошлые на 10%. */
        const val DECAY = 0.9

        fun fromJson(json: JSONObject) = PingRecord(
            ok = json.optDouble("ok", 0.0),
            fail = json.optDouble("fail", 0.0),
            avgMs = json.optDouble("avg", 0.0),
            lastMs = json.optInt("last"),
            lastOkAt = json.optLong("lastOk"),
            checks = json.optInt("n"),
        )
    }
}

/**
 * Статистика пингов по серверам. Ключ — адрес и порт, а не имя: так история
 * переживает переименования в подписке и общая у обоих ядер.
 *
 * История копится отдельно для Wi-Fi и мобильной сети ([kindOf] — класс
 * текущей сети): сервер, который работает дома, может быть заблокирован у
 * мобильного оператора. Записи без класса (до разделения) остаются общими и
 * используются, пока для сети своей истории нет.
 */
class PingStats(context: Context, private val kindOf: () -> String = { "" }) {
    private val prefs = context.getSharedPreferences("ping_stats", Context.MODE_PRIVATE)
    private val records = mutableMapOf<String, PingRecord>()

    init {
        val saved = prefs.getString("stats", null)
        if (saved != null) {
            runCatching {
                val json = JSONObject(saved)
                json.keys().forEach { key -> records[key] = PingRecord.fromJson(json.getJSONObject(key)) }
            }
        }
    }

    private fun scoped(key: String, kind: String = kindOf()): String = if (kind.isEmpty()) key else "$kind|$key"

    private fun lookup(key: String, kind: String = kindOf()): PingRecord? = records[scoped(key, kind)] ?: records[key]

    @Synchronized
    fun get(node: ServerNode): PingRecord? = lookup(keyOf(node))

    @Synchronized
    fun record(node: ServerNode, success: Boolean, ms: Int) {
        val key = keyOf(node)
        val scopedKey = scoped(key)
        // Первая запись для сети начинается с общей истории, а не с нуля.
        records[scopedKey] = (records[scopedKey] ?: records[key] ?: PingRecord()).record(success, ms)
    }

    /** Порядок проверки: сначала те, что чаще работают и отвечают быстрее. */
    @Synchronized
    fun order(nodes: List<ServerNode>): List<ServerNode> =
        nodes.sortedByDescending { lookup(keyOf(it))?.score ?: UNKNOWN_SCORE }

    /** История для текущей сети: ключ — адрес:порт (см. [keyOf]). */
    @Synchronized
    fun snapshot(kind: String = kindOf()): Map<String, PingRecord> {
        val out = mutableMapOf<String, PingRecord>()
        records.forEach { (key, value) -> if ('|' !in key) out[key] = value }
        if (kind.isNotEmpty()) {
            val prefix = "$kind|"
            records.forEach { (key, value) -> if (key.startsWith(prefix)) out[key.removePrefix(prefix)] = value }
        }
        return out
    }

    /** Забыть всю историю проверок. */
    @Synchronized
    fun clear() {
        records.clear()
        prefs.edit().remove("stats").apply()
    }

    @Synchronized
    fun save() {
        val json = JSONObject()
        // Держим не больше 1000 записей: самые давно работавшие уходят первыми.
        records.entries.sortedByDescending { it.value.lastOkAt }.take(1000).forEach { (key, value) -> json.put(key, value.toJson()) }
        prefs.edit().putString("stats", json.toString()).apply()
    }

    companion object {
        /** Новые серверы проверяются сразу после надёжных, но раньше плохих. */
        private const val UNKNOWN_SCORE = 0.9

        fun keyOf(node: ServerNode): String =
            if (node.server.isNotEmpty() && node.port > 0) "${node.server.lowercase()}:${node.port}" else "name:${node.name}"
    }
}

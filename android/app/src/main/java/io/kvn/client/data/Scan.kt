package io.kvn.client.data

import org.json.JSONArray
import org.json.JSONObject

/** Набор целей для проверки («Заблокированные», «ИИ»…). */
data class ScanPreset(val id: String, val name: String, val description: String, val targets: List<String>)

data class ScanStep(val id: String, val title: String, val status: String, val detail: String, val ms: Double)

data class ScanPath(val id: String, val title: String, val verdict: String, val summary: String, val steps: List<ScanStep>)

data class ScanDns(val resolver: String, val via: String, val ips: List<String>, val verdict: String, val error: String)

/** Разбор одной цели из libcore.Scan. */
data class ScanResult(
    val target: String,
    val verdict: String,
    val summary: String,
    val ip: String,
    val at: Long,
    val ms: Double,
    val paths: List<ScanPath>,
    val dns: List<ScanDns>,
    val hints: List<String>,
    val error: String? = null,
) {
    companion object {
        fun failed(target: String, message: String) = ScanResult(
            target = target, verdict = "error", summary = message, ip = "", at = System.currentTimeMillis() / 1000,
            ms = 0.0, paths = emptyList(), dns = emptyList(), hints = emptyList(), error = message,
        )

        fun fromJson(json: JSONObject) = ScanResult(
            target = json.optString("target"),
            verdict = json.optString("verdict"),
            summary = json.optString("summary"),
            ip = json.optString("ip"),
            at = json.optLong("at"),
            ms = json.optDouble("ms", 0.0),
            paths = json.optJSONArray("paths").objects().map { path ->
                ScanPath(
                    id = path.optString("id"),
                    title = path.optString("title"),
                    verdict = path.optString("verdict"),
                    summary = path.optString("summary"),
                    steps = path.optJSONArray("steps").objects().map { step ->
                        ScanStep(
                            id = step.optString("id"),
                            title = step.optString("title"),
                            status = step.optString("status"),
                            detail = step.optString("detail"),
                            ms = step.optDouble("ms", 0.0),
                        )
                    },
                )
            },
            dns = json.optJSONArray("dns").objects().map { answer ->
                ScanDns(
                    resolver = answer.optString("resolver"),
                    via = answer.optString("via"),
                    ips = answer.optJSONArray("ips").strings(),
                    verdict = answer.optString("verdict"),
                    error = answer.optString("error"),
                )
            },
            hints = json.optJSONArray("hints").strings(),
        )
    }
}

fun parseScanPresets(json: String): List<ScanPreset> = JSONArray(json).objects().map {
    ScanPreset(
        id = it.optString("id"),
        name = it.optString("name"),
        description = it.optString("description"),
        targets = it.optJSONArray("targets").strings(),
    )
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
}

/** Результат настоящей проверки сервера (libcore.TestNode). */
data class NodeTest(
    val tcpMs: Int,
    val ok: Boolean,
    val ms: Int,
    val status: Int,
    val error: String,
    /** ok — работает; silent — пингуется, но ничего не открывает; down — недоступен; error — ядро не приняло. */
    val verdict: String,
) {
    companion object {
        fun fromJson(json: JSONObject) = NodeTest(
            tcpMs = json.optInt("tcpMs"),
            ok = json.optBoolean("ok"),
            ms = json.optInt("ms"),
            status = json.optInt("status"),
            error = json.optString("error"),
            verdict = json.optString("verdict"),
        )

        fun failed(message: String) = NodeTest(0, false, 0, 0, message, "error")
    }
}

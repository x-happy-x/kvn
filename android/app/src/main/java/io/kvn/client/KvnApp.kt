package io.kvn.client

import android.app.Application
import io.kvn.client.core.CoreBridge
import io.kvn.client.data.NetworkId
import io.kvn.client.data.NetworkWatcher
import io.kvn.client.data.PingStats
import io.kvn.client.data.Repository

class KvnApp : Application() {
    lateinit var repository: Repository
        private set

    /** Сеть под VPN (Wi-Fi или мобильная): для свежести пингов и статистики. */
    val network: NetworkWatcher by lazy { NetworkWatcher(this) }

    /**
     * Статистика пингов: общая для экрана и VPN-сервиса (авто-выбор сервера),
     * отдельная для Wi-Fi и мобильной сети.
     */
    val pingStats: PingStats by lazy {
        PingStats(this) { network.current.kind.takeIf { it != NetworkId.KIND_NONE }.orEmpty() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CoreBridge.init(filesDir.resolve("core").absolutePath)
        repository = Repository(this)
    }

    companion object {
        lateinit var instance: KvnApp
            private set
    }
}

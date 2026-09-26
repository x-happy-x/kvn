package io.kvn.client

import android.app.Application
import io.kvn.client.core.CoreBridge
import io.kvn.client.data.PingStats
import io.kvn.client.data.Repository

class KvnApp : Application() {
    lateinit var repository: Repository
        private set

    /** Статистика пингов: общая для экрана и VPN-сервиса (авто-выбор сервера). */
    val pingStats: PingStats by lazy { PingStats(this) }

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

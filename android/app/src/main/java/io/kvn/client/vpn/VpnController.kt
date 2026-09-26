package io.kvn.client.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.kvn.client.core.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Idle : VpnState
    data object Connecting : VpnState
    data class Connected(val since: Long, val engine: Engine, val nodeName: String) : VpnState

    /** Сервис включён, но туннель снят: например, телефон в доверенной сети Wi-Fi. */
    data class Paused(val reason: String) : VpnState
    data object Stopping : VpnState
    data class Failed(val message: String) : VpnState
}

/** Общая точка управления туннелем для экрана, плитки и уведомления. */
object VpnController {
    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    internal fun update(state: VpnState) {
        _state.value = state
    }

    /** Включён ли VPN с точки зрения пользователя — в том числе на паузе. */
    val isActive: Boolean
        get() = when (_state.value) {
            is VpnState.Connected, VpnState.Connecting, is VpnState.Paused -> true
            else -> false
        }

    /** Запуск; разрешение VpnService.prepare() должно быть уже получено. */
    fun start(context: Context) = send(context, KvnVpnService.ACTION_START)

    /** Перезапуск на текущих настройках: смена ядра, сервера или правил. */
    fun restart(context: Context) = send(context, KvnVpnService.ACTION_RESTART)

    fun stop(context: Context) = send(context, KvnVpnService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        val intent = Intent(context, KvnVpnService::class.java).setAction(action)
        if (action == KvnVpnService.ACTION_STOP) {
            context.startService(intent)
        } else {
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

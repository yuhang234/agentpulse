package dev.agentpulse.android.di

import android.content.Context
import dev.agentpulse.android.data.PulseRepository
import dev.agentpulse.android.data.SettingsStore
import dev.agentpulse.android.data.remote.AgentPulseApi
import dev.agentpulse.android.data.remote.AgentPulseSocket
import dev.agentpulse.android.data.remote.HttpClientFactory
import dev.agentpulse.android.notifications.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons. Built once in [dev.agentpulse.android.PulseApplication] so the
 * repository (and therefore the WebSocket) survives configuration changes.
 */
class AppContainer(context: Context) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val notifier = AlertNotifier(context, settingsStore = settings)

    private val httpClient = HttpClientFactory.create()
    private val api = AgentPulseApi(httpClient, settingsProvider = { settings.current() })
    private val socket = AgentPulseSocket(httpClient)

    val repository = PulseRepository(
        api = api,
        socket = socket,
        settingsStore = settings,
        scope = scope,
    )
}

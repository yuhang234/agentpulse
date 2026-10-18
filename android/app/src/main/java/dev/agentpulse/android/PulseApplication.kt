package dev.agentpulse.android

import android.app.Application
import dev.agentpulse.android.di.AppContainer
import kotlinx.coroutines.launch

class PulseApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannels()

        // Start the repository once, app-lifetime: the WebSocket and REST poll loops keep running
        // while the process lives so local notifications (report 4.4) work in the background too.
        container.repository.start()

        // Forward newly-raised alerts to the system notification pipeline (heads-up / silent).
        container.scope.launch {
            container.repository.raisedAlerts.collect { batch ->
                batch.forEach { container.notifier.notify(it) }
            }
        }
    }
}

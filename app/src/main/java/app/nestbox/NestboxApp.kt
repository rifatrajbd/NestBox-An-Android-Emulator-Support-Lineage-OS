package app.nestbox

import android.app.Application
import app.nestbox.engine.ServiceEngine
import app.nestbox.engine.VmEngine
import app.nestbox.host.HostInfo
import app.nestbox.service.Notifications
import app.nestbox.settings.SettingsStore
import app.nestbox.settings.SetupStore
import app.nestbox.vm.VmRepository
import app.nestbox.xiaomi.SetupChecks

/**
 * Manual wiring; the app is small enough not to need Hilt.
 * This class also runs in every machine process (":vm1"…), where none of the UI objects are needed.
 */
class NestboxApp : Application() {
    lateinit var host: HostInfo
    lateinit var repo: VmRepository
    lateinit var engine: VmEngine
    lateinit var settings: SettingsStore
    lateinit var setup: SetupStore
    lateinit var checks: SetupChecks

    val isUiProcess: Boolean get() = getProcessName() == packageName

    override fun onCreate() {
        super.onCreate()
        if (!isUiProcess) return
        Notifications.ensureChannels(this)
        host = HostInfo(this)
        repo = VmRepository(this)
        settings = SettingsStore(this)
        setup = SetupStore(this)
        checks = SetupChecks(this)
        // Each machine runs in its own VmService process with the container engine.
        engine = ServiceEngine(this) { settings.settings.value }
    }
}

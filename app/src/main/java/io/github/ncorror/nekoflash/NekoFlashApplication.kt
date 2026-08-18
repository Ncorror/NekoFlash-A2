package io.github.ncorror.nekoflash

import android.app.Application
import io.github.ncorror.nekoflash.entry.EntrySessionGate
import io.github.ncorror.nekoflash.entry.SharedPreferencesEntrySessionPersistence
import io.github.ncorror.nekoflash.usb.session.UsbSessionCoordinator

class NekoFlashApplication : Application() {
    lateinit var entrySessionGate: EntrySessionGate
        private set

    lateinit var usbSessionCoordinator: UsbSessionCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        entrySessionGate = EntrySessionGate(SharedPreferencesEntrySessionPersistence(this))
        usbSessionCoordinator = UsbSessionCoordinator(this)
    }
}

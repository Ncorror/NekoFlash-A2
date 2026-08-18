package io.github.ncorror.nekoflash

import android.app.Application
import io.github.ncorror.nekoflash.usb.session.UsbSessionCoordinator

class NekoFlashApplication : Application() {
    lateinit var usbSessionCoordinator: UsbSessionCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        usbSessionCoordinator = UsbSessionCoordinator(this).also { it.start() }
    }
}

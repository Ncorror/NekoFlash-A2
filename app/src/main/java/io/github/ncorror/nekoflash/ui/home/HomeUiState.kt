package io.github.ncorror.nekoflash.ui.home

data class HomeUiState(
    val device: String? = null,
    val mode: String? = null,
    val usbConnected: Boolean = false,
    val slot: String? = null,
    val topology: String? = null,
    val unlockState: String? = null,
    val activeOperation: String? = null,
)

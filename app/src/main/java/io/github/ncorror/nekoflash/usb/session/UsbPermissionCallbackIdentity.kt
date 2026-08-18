package io.github.ncorror.nekoflash.usb.session

/**
 * Builds a distinct USB permission callback action for every coordinator activation.
 *
 * PendingIntent identity ignores extras, so the activation generation must be part
 * of an Intent field used by filterEquals(). A process token additionally prevents
 * a callback created before process death from matching a receiver in a new process.
 */
internal class UsbPermissionCallbackIdentity(
    private val actionPrefix: String,
    private val processToken: String,
) {
    init {
        require(actionPrefix.isNotBlank()) { "USB permission action prefix must not be blank" }
        require(processToken.isNotBlank()) { "USB permission process token must not be blank" }
    }

    data class Callback(
        val generation: Long,
        val action: String,
    )

    private var generation = 0L

    fun nextCallback(): Callback {
        generation += 1L
        return Callback(
            generation = generation,
            action = "$actionPrefix.$processToken.$generation",
        )
    }
}

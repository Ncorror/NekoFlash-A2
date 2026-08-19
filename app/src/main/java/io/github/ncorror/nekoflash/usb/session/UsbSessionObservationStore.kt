package io.github.ncorror.nekoflash.usb.session

/**
 * Small in-process observation channel owned by UsbSessionCoordinator.
 *
 * Listener replacement is generation-guarded so an Activity that is being
 * destroyed cannot clear a newer Activity's listener after configuration change.
 */
internal class UsbSessionObservationStore(
    initial: UsbSessionObservation = UsbSessionObservation(),
) {
    private var observation = initial
    private var listener: ((UsbSessionObservation) -> Unit)? = null
    private var listenerGeneration = 0L

    fun current(): UsbSessionObservation = observation

    fun replaceListener(listener: (UsbSessionObservation) -> Unit): Long {
        listenerGeneration += 1L
        this.listener = listener
        listener(observation)
        return listenerGeneration
    }

    fun clearListener(generation: Long) {
        if (listenerGeneration == generation) {
            listener = null
        }
    }

    fun publish(observation: UsbSessionObservation) {
        this.observation = observation
        listener?.invoke(observation)
    }
}

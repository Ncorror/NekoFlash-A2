package io.github.ncorror.nekoflash.usb.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.UUID

/** One process-local structured USB evidence run. Export/share UI is added separately. */
class UsbDiagnosticStore(context: Context) {
    private val rootDirectory: File = (
        context.getExternalFilesDir("diagnostics")
            ?: File(context.filesDir, "diagnostics")
        ).apply { mkdirs() }

    private val directory: File = File(
        rootDirectory,
        "usb-run-${System.currentTimeMillis()}-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    private val eventsFile = File(directory, "usb-events.txt")

    fun event(level: String, event: String, detail: String = "") {
        val line = buildString {
            append(Instant.now())
            append(' ')
            append(level.singleLine())
            append(' ')
            append(event.singleLine())
            if (detail.isNotBlank()) {
                append(' ')
                append(detail.singleLine())
            }
            append('\n')
        }
        writeSafely(eventsFile, append = true, content = line)
    }

    fun writeSnapshot(snapshot: UsbSessionSnapshot) {
        writeSafely(
            File(directory, "usb-session-${snapshot.sessionId}.json"),
            append = false,
            content = snapshot.toJson() + "\n",
        )
        writeSafely(
            File(directory, "usb-session-${snapshot.sessionId}.txt"),
            append = false,
            content = snapshot.toDiagnosticText(),
        )
    }

    /** Returns only this process run, never the parent containing older runs. */
    fun directoryPath(): String = directory.absolutePath

    private fun writeSafely(file: File, append: Boolean, content: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to write USB diagnostics: ${file.name}", error)
        }
    }

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

    private companion object {
        const val TAG = "NekoFlashUsb"
    }
}

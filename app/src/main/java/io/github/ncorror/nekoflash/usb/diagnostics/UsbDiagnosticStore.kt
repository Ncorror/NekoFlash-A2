package io.github.ncorror.nekoflash.usb.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** One process-local structured USB evidence run. */
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
    private val ioLock = Any()

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
        synchronized(ioLock) {
            writeSafely(eventsFile, append = true, content = line)
        }
    }

    fun writeSnapshot(snapshot: UsbSessionSnapshot) {
        synchronized(ioLock) {
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
    }

    /**
     * Creates a coherent local ZIP snapshot while holding the diagnostics lock,
     * then copies that finished archive to the caller-owned destination outside
     * the lock so a slow document provider cannot block USB event recording.
     */
    fun exportArchive(
        output: OutputStream,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): UsbDiagnosticsZipExporter.Result {
        val temporaryArchive = File.createTempFile("usb-diagnostics-export-", ".zip", rootDirectory)
        return try {
            val result = synchronized(ioLock) {
                temporaryArchive.outputStream().buffered().use { localOutput ->
                    UsbDiagnosticsZipExporter(directory).writeArchive(
                        output = localOutput,
                        exportedAtEpochMs = exportedAtEpochMs,
                    )
                }
            }
            temporaryArchive.inputStream().buffered().use { input ->
                input.copyTo(output)
            }
            output.flush()
            result
        } finally {
            if (temporaryArchive.exists() && !temporaryArchive.delete()) {
                Log.w(TAG, "Unable to remove temporary diagnostics archive: ${temporaryArchive.name}")
            }
        }
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

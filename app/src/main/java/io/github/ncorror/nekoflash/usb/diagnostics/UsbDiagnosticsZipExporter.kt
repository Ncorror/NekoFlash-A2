package io.github.ncorror.nekoflash.usb.diagnostics

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure JVM ZIP writer for one [UsbDiagnosticStore] process run.
 *
 * The caller owns [output]. Only the bounded USB evidence filename families are
 * exported, so unrelated files that may appear beside the run are not leaked.
 */
class UsbDiagnosticsZipExporter(
    private val runDirectory: File,
) {
    data class Result(
        val sourceFileCount: Int,
        val sourceBytes: Long,
    )

    fun writeArchive(
        output: OutputStream,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): Result {
        val sourceFiles = exportableFiles()
        val sourceBytes = sourceFiles.sumOf(File::length)
        ZipOutputStream(
            BufferedOutputStream(NonClosingOutputStream(output)),
        ).use { zip ->
            writeTextEntry(
                zip = zip,
                name = MANIFEST_NAME,
                content = manifestText(sourceFiles, sourceBytes, exportedAtEpochMs),
            )
            sourceFiles.forEach { file ->
                writeFileEntry(zip, file)
            }
        }
        output.flush()

        return Result(
            sourceFileCount = sourceFiles.size,
            sourceBytes = sourceBytes,
        )
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }

    private fun exportableFiles(): List<File> =
        runDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && isExportableName(file.name) }
            .sortedBy(File::getName)

    private fun manifestText(
        files: List<File>,
        sourceBytes: Long,
        exportedAtEpochMs: Long,
    ): String = buildString {
        appendLine("schema=$ARCHIVE_SCHEMA")
        appendLine("exportedAt=${Instant.ofEpochMilli(exportedAtEpochMs)}")
        appendLine("sourceFileCount=${files.size}")
        appendLine("sourceBytes=$sourceBytes")
        files.forEach { file -> appendLine("file=${file.name}") }
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        putEntry(zip, name) { zip.write(bytes) }
    }

    private fun writeFileEntry(zip: ZipOutputStream, file: File) {
        putEntry(zip, file.name) {
            file.inputStream().buffered().use { input -> input.copyTo(zip) }
        }
    }

    private inline fun putEntry(zip: ZipOutputStream, name: String, write: () -> Unit) {
        val entry = ZipEntry(name).apply {
            // Archive bytes should not depend on filesystem modification timestamps.
            time = 0L
        }
        zip.putNextEntry(entry)
        try {
            write()
        } finally {
            zip.closeEntry()
        }
    }

    companion object {
        const val ARCHIVE_SCHEMA = "io.github.ncorror.nekoflash.usb-diagnostics-export.v1"
        const val MANIFEST_NAME = "export-manifest.txt"

        private val FILE_NAME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss'Z'")
            .withZone(ZoneOffset.UTC)

        fun suggestedFileName(exportedAtEpochMs: Long = System.currentTimeMillis()): String =
            "NekoFlash-A2-diagnostics-${FILE_NAME_FORMATTER.format(Instant.ofEpochMilli(exportedAtEpochMs))}.zip"

        private fun isExportableName(name: String): Boolean =
            name == "usb-events.txt" ||
                (
                    name.startsWith("usb-session-") &&
                        (name.endsWith(".json") || name.endsWith(".txt"))
                    )
    }
}

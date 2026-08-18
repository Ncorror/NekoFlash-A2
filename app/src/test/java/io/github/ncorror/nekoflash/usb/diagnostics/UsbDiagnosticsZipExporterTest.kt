package io.github.ncorror.nekoflash.usb.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDiagnosticsZipExporterTest {
    @Test
    fun `archive contains only USB evidence files in stable name order`() = withRunDirectory { directory ->
        directory.resolve("usb-session-z.txt").writeText("text-z")
        directory.resolve("ignored.bin").writeBytes(byteArrayOf(1, 2, 3))
        directory.resolve("usb-events.txt").writeText("events")
        directory.resolve("usb-session-a.json").writeText("{\"a\":1}")

        val output = ByteArrayOutputStream()
        val result = UsbDiagnosticsZipExporter(directory).writeArchive(output, exportedAtEpochMs = 0L)
        val entries = unzip(output.toByteArray())

        assertEquals(3, result.sourceFileCount)
        assertEquals(
            listOf(
                UsbDiagnosticsZipExporter.MANIFEST_NAME,
                "usb-events.txt",
                "usb-session-a.json",
                "usb-session-z.txt",
            ),
            entries.keys.toList(),
        )
        assertEquals("events", entries.getValue("usb-events.txt").toString(Charsets.UTF_8))
        assertEquals("{\"a\":1}", entries.getValue("usb-session-a.json").toString(Charsets.UTF_8))
        assertEquals("text-z", entries.getValue("usb-session-z.txt").toString(Charsets.UTF_8))
        assertFalse(entries.containsKey("ignored.bin"))
    }

    @Test
    fun `manifest identifies schema timestamp counts and included files`() = withRunDirectory { directory ->
        directory.resolve("usb-events.txt").writeText("abc")
        directory.resolve("usb-session-1.txt").writeText("12345")

        val output = ByteArrayOutputStream()
        val result = UsbDiagnosticsZipExporter(directory).writeArchive(output, exportedAtEpochMs = 0L)
        val manifest = unzip(output.toByteArray())
            .getValue(UsbDiagnosticsZipExporter.MANIFEST_NAME)
            .toString(Charsets.UTF_8)

        assertEquals(2, result.sourceFileCount)
        assertEquals(8L, result.sourceBytes)
        assertTrue(manifest.contains("schema=${UsbDiagnosticsZipExporter.ARCHIVE_SCHEMA}\n"))
        assertTrue(manifest.contains("exportedAt=1970-01-01T00:00:00Z\n"))
        assertTrue(manifest.contains("sourceFileCount=2\n"))
        assertTrue(manifest.contains("sourceBytes=8\n"))
        assertTrue(manifest.contains("file=usb-events.txt\n"))
        assertTrue(manifest.contains("file=usb-session-1.txt\n"))
    }

    @Test
    fun `empty run still produces a valid manifest-only archive`() = withRunDirectory { directory ->
        val output = ByteArrayOutputStream()
        val result = UsbDiagnosticsZipExporter(directory).writeArchive(output, exportedAtEpochMs = 0L)
        val entries = unzip(output.toByteArray())

        assertEquals(0, result.sourceFileCount)
        assertEquals(0L, result.sourceBytes)
        assertEquals(listOf(UsbDiagnosticsZipExporter.MANIFEST_NAME), entries.keys.toList())
    }

    @Test
    fun `suggested filename is UTC and deterministic`() {
        assertEquals(
            "NekoFlash-A2-diagnostics-19700101-000000Z.zip",
            UsbDiagnosticsZipExporter.suggestedFileName(0L),
        )
    }

    @Test
    fun `archive leaves caller owned output stream open`() = withRunDirectory { directory ->
        directory.resolve("usb-events.txt").writeText("events")
        val output = CloseTrackingOutputStream()

        UsbDiagnosticsZipExporter(directory).writeArchive(output, exportedAtEpochMs = 0L)

        assertFalse(output.closed)
        assertEquals(
            "events",
            unzip(output.toByteArray()).getValue("usb-events.txt").toString(Charsets.UTF_8),
        )
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun <T> withRunDirectory(block: (java.io.File) -> T): T {
        val directory = Files.createTempDirectory("nekoflash-usb-diagnostics").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }
}

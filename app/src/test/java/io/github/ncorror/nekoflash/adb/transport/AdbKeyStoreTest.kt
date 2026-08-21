package io.github.ncorror.nekoflash.adb.transport

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyStoreTest {
    @Test
    fun `key material persists and public payload is nul terminated mincrypt line`() {
        val directory = Files.createTempDirectory("nekoflash-adbkey").toFile()
        try {
            val first = AdbKeyStore(directory) { }
            val firstPublic = first.publicKeyPayload()
            val second = AdbKeyStore(directory) { }
            val secondPublic = second.publicKeyPayload()

            assertArrayEquals(firstPublic, secondPublic)
            assertEquals(0, firstPublic.last().toInt())
            assertTrue(firstPublic.dropLast(1).toByteArray().toString(Charsets.US_ASCII).endsWith(" NekoFlash@Android"))
            assertTrue(directory.resolve("adbkey.pk8").isFile)
            assertTrue(directory.resolve("adbkey.pub").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `auth token signature keeps rsa 2048 contract`() {
        val directory = Files.createTempDirectory("nekoflash-adbsign").toFile()
        try {
            val keyStore = AdbKeyStore(directory) { }
            val signature = keyStore.signToken(ByteArray(20) { it.toByte() })

            assertEquals(256, signature.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}

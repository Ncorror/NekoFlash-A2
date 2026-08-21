package io.github.ncorror.nekoflash.adb.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPacketDispatcherTest {
    @Test
    fun `single reader publishes complete packet then stops on closed source`() {
        val reads = AtomicInteger(0)
        val payload = "device::features=shell_v2".toByteArray()
        val dispatcher = AdbPacketDispatcher(
            source = {
                if (reads.getAndIncrement() == 0) {
                    AdbPacketDispatcher.ReadResult.PacketReady(
                        AdbPacketDispatcher.Packet(
                            command = 0x4E584E43L,
                            arg0 = 0x01000000,
                            arg1 = 1_048_576,
                            checksum = payload.sumOf { it.toInt() and 0xFF },
                            magic = 0x4E584E43.inv(),
                            payload = payload,
                        ),
                    )
                } else {
                    AdbPacketDispatcher.ReadResult.Closed
                }
            },
            onFailure = { _, _ -> error("failure callback must not run") },
        )

        assertTrue(dispatcher.start())
        val packet = dispatcher.take(500)

        assertNotNull(packet)
        assertEquals(payload.toList(), packet!!.payload.toList())
        repeat(50) {
            if (!dispatcher.snapshot().running) return@repeat
            Thread.sleep(5)
        }
        assertFalse(dispatcher.snapshot().running)
        assertEquals(1L, dispatcher.snapshot().packetsRead)
    }

    @Test
    fun `source failure is terminal and reported once`() {
        val callback = CountDownLatch(1)
        val failures = AtomicInteger(0)
        val dispatcher = AdbPacketDispatcher(
            source = {
                AdbPacketDispatcher.ReadResult.Failed(
                    AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH,
                    "bad checksum",
                )
            },
            onFailure = { code, message ->
                assertEquals(AdbPacketDispatcher.FailureCode.CHECKSUM_MISMATCH, code)
                assertEquals("bad checksum", message)
                failures.incrementAndGet()
                callback.countDown()
            },
        )

        assertTrue(dispatcher.start())
        assertTrue(callback.await(500, TimeUnit.MILLISECONDS))
        assertEquals(1, failures.get())
        assertFalse(dispatcher.snapshot().running)
        assertEquals(1L, dispatcher.snapshot().readFailures)
    }
}

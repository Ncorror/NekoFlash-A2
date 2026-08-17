package io.github.ncorror.nekoflash.adb.codec

/** Pure ADB payload-checksum rules extracted from the legacy implementation. */
object AdbChecksum {
    const val VERSION_WITH_CHECKSUM: Int = 0x01000000
    const val VERSION_SKIP_CHECKSUM: Int = 0x01000001

    fun compute(payload: ByteArray): Int {
        var checksum = 0
        for (byte in payload) {
            checksum += byte.toInt() and 0xFF
        }
        return checksum
    }

    fun isRequired(localVersion: Int, peerVersion: Int): Boolean =
        minOf(localVersion, peerVersion) < VERSION_SKIP_CHECKSUM

    fun matches(
        expected: Int,
        payload: ByteArray,
        localVersion: Int,
        peerVersion: Int,
    ): Boolean = !isRequired(localVersion, peerVersion) || expected == compute(payload)
}

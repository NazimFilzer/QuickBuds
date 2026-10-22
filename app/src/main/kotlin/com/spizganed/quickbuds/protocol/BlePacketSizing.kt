package com.spizganed.quickbuds.protocol

/** Split only the documented feature-id query; never invent fragmentation for feature writes. */
internal object BlePacketSizing {
    fun packets(frame: ByteArray, maxBytes: Int): List<ByteArray> {
        if (frame.size <= maxBytes) return listOf(frame)
        require(maxBytes >= 11 && frame.size in 11..127 && frame[0] == 0xAA.toByte() &&
            frame[4] == 0x0D.toByte() && frame[5] == 1.toByte() && frame[8] == 0.toByte() &&
            (frame[1].toInt() and 0xFF) == frame.size - 2 &&
            (frame[7].toInt() and 0xFF) == frame.size - 9 &&
            (frame[9].toInt() and 0xFF) == frame.size - 10) {
            "Packet exceeds BLE MTU and has no documented split form"
        }
        return frame.copyOfRange(10, frame.size).toList().chunked(maxBytes - 10).map { ids ->
            val header = frame.copyOfRange(0, 9)
            header[1] = (8 + ids.size).toByte()
            header[7] = (1 + ids.size).toByte()
            header + byteArrayOf(ids.size.toByte()) + ids.toByteArray()
        }
    }
}

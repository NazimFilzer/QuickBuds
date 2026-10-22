package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.protocol.OppoPacketFramer
import com.spizganed.quickbuds.protocol.BlePacketSizing

fun runConfirmedToggleTests(): Int {
    val tests = linkedMapOf<String, () -> Unit>(
        "unknown firmware state cannot be changed blindly" to {
            val state = ConfirmedToggle()
            check(state.snapshot().confirmed == null && state.request(true) == null)
        },
        "pending choice survives a new screen reading the service state" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            state.request(true)
            check(state.snapshot().displayed && state.snapshot().pending)
            check(state.snapshot().confirmed == false)
        },
        "an old off reading does not complete an on request" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            state.request(true)
            state.confirm(false)
            check(state.snapshot().pending && state.snapshot().displayed)
        },
        "matching read-back completes the request and cancels its timeout" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            val request = state.request(true)!!
            state.confirm(true)
            check(state.snapshot().confirmed == true && !state.snapshot().pending)
            check(!state.expire(request))
        },
        "timeout restores only the last firmware-confirmed value" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            check(state.expire(state.request(true)!!))
            check(!state.snapshot().displayed && !state.snapshot().pending)
        },
        "disconnect invalidates an earlier timeout and cached state" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            val token = state.request(true)!!
            state.reset()
            check(!state.expire(token) && state.snapshot().confirmed == null)
        },
        "a stale timeout cannot cancel a newer request" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            val old = state.request(true)!!
            check(state.expire(old))
            state.request(true)
            check(!state.expire(old) && state.snapshot().pending)
        },
        "overlapping changes are refused while confirmation is pending" to {
            val state = ConfirmedToggle()
            state.confirm(false)
            state.request(true)
            check(state.request(false) == null && state.snapshot().displayed)
        },
        "fragmented BLE notifications reassemble a feature status reply" to {
            val packet = byteArrayOf(0xAA.toByte(), 0x0B, 0, 0, 0x0D, 0x81.toByte(), 1, 4, 0, 0, 1, 0x11, 1)
            val framer = OppoPacketFramer()
            check(framer.append(packet.copyOfRange(0, 5), 5).isEmpty())
            val result = framer.append(packet.copyOfRange(5, packet.size), packet.size - 5)
            check(result.single().contentEquals(packet))
        },
        "feature queries fit the minimum BLE MTU without losing ids" to {
            val ids = byteArrayOf(5, 4, 11, 17, 24, 6, 27, 29, 30, 39, 40, 55)
            val frame = byteArrayOf(0xAA.toByte(), 20, 0, 0, 13, 1, 0, 13, 0, 12) + ids
            val packets = BlePacketSizing.packets(frame, 20)
            check(packets.size == 2 && packets.all { it.size <= 20 })
            check(packets.flatMap { it.drop(10) }.toByteArray().contentEquals(ids))
            check(packets.all { (it[1].toInt() and 255) == it.size - 2 && (it[7].toInt() and 255) == it.size - 9 })
        },
        "packets fitting the negotiated MTU stay byte-for-byte unchanged" to {
            val bytes = byteArrayOf(0xAA.toByte(), 9, 0, 0, 3, 4, 1, 2, 0, 17, 1)
            check(BlePacketSizing.packets(bytes, 20).single().contentEquals(bytes))
        },
        "oversized writes are refused rather than guessing fragmentation" to {
            val bytes = byteArrayOf(0xAA.toByte(), 30, 0, 0, 3, 4) + ByteArray(26)
            check(runCatching { BlePacketSizing.packets(bytes, 20) }.isFailure)
        },
        "notifications from separate characteristics never share a partial frame" to {
            val packet = byteArrayOf(0xAA.toByte(), 8, 0, 0, 3, 0x84.toByte(), 1, 1, 0, 0)
            val one = OppoPacketFramer()
            val two = OppoPacketFramer()
            check(one.append(packet.copyOfRange(0, 4), 4).isEmpty())
            check(two.append(packet, packet.size).single().contentEquals(packet))
            check(one.append(packet.copyOfRange(4, packet.size), packet.size - 4).single().contentEquals(packet))
        }
    )
    tests.forEach { (name, test) -> test(); println("PASS: $name") }
    return tests.size
}

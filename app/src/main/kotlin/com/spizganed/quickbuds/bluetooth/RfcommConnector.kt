package com.spizganed.quickbuds.bluetooth

import java.io.Closeable
import java.util.UUID

/** The socket policy is independent of Android so failures and cleanup can be tested on the JVM. */
internal class RfcommConnector<S : Closeable>(
    private val createSocket: (UUID, secure: Boolean) -> S,
    private val connectSocket: (S) -> Unit,
    private val createLegacySocket: () -> S,
    private val log: (String) -> Unit
) {
    companion object {
        val STANDARD_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    fun connect(
        knownUuids: List<UUID>,
        advertisedUuids: Set<UUID> = emptySet(),
        allowStandardSpp: Boolean = false
    ): S? {
        // The Nord Buds 4 Pro owner reports standard SPP, without either OPPO UUID. Its protocol
        // remains unverified: opt in for that model only, and only when the service is advertised.
        val candidates = if (allowStandardSpp && STANDARD_SPP_UUID in advertisedUuids)
            knownUuids + STANDARD_SPP_UUID else knownUuids
        // Cached SDP records are a hint, not a filter: missing/stale records must not block a UUID.
        val ordered = candidates.distinct().sortedBy { if (it in advertisedUuids) 0 else 1 }
        // Keep the existing authenticated paths first. Some phone/earbud combinations cannot
        // authenticate the control service; Android provides insecure RFCOMM for that case.
        for (secure in listOf(true, false)) {
            for (uuid in ordered) {
                val label = "${if (secure) "Secure" else "Insecure"} UUID $uuid"
                attempt(label) { createSocket(uuid, secure) }?.let { return it }
            }
        }
        // Compatibility with existing models only. There is no evidence that Nord Buds 4 Pro
        // use channel 15; never scan unrelated RFCOMM channels or send commands to audio services.
        return attempt("Legacy channel 15", createLegacySocket)
    }

    private fun attempt(label: String, create: () -> S): S? {
        var socket: S? = null
        try {
            log("Trying $label")
            socket = create()
            connectSocket(socket)
            log("Connected via $label")
            return socket
        } catch (e: Exception) {
            // Reflection wraps the useful Bluetooth error. Preserve it in the exported log.
            val reason = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
            log("$label failed: ${reason.javaClass.simpleName}: ${reason.message ?: "no detail"}")
            try { socket?.close() } catch (_: Exception) {}
            if (reason is SecurityException) throw reason
            return null
        }
    }
}

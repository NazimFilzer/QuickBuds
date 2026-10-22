package com.spizganed.quickbuds.bluetooth

import java.io.Closeable
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID

private val first = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
private val second = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5")
private val known = listOf(first, second)

private class FakeSocket(val label: String, val closeFails: Boolean = false) : Closeable {
    var closed = false
    override fun close() {
        closed = true
        if (closeFails) throw IOException("close failed")
    }
}

private class Fixture(
    val success: String? = null,
    val factoryFailure: String? = null,
    val closeFails: Boolean = false,
    val failure: Exception = IOException("read ret: -1")
) {
    val attempts = mutableListOf<String>()
    val sockets = mutableListOf<FakeSocket>()
    val messages = mutableListOf<String>()
    private fun create(label: String): FakeSocket {
        attempts += label
        if (label == factoryFailure) throw IOException("socket creation failed")
        return FakeSocket(label, closeFails).also { sockets += it }
    }
    val connector = RfcommConnector(
        createSocket = { uuid: UUID, secure: Boolean -> create("$secure:$uuid") },
        connectSocket = { socket: FakeSocket -> if (socket.label != success) throw failure },
        createLegacySocket = { create("legacy") },
        log = { messages += it }
    )
}

fun main() {
    val tests = linkedMapOf<String, () -> Unit>(
        "existing secure connection stops fallback and stays open" to {
            val f = Fixture(success = "true:$first")
            val socket = f.connector.connect(known)
            check(socket === f.sockets.single())
            check(!socket.closed && f.attempts.size == 1)
        },
        "secure failures reach insecure service discovery" to {
            val f = Fixture(success = "false:$second")
            val socket = f.connector.connect(known)
            check(f.attempts == listOf("true:$first", "true:$second", "false:$first", "false:$second"))
            check(f.sockets.dropLast(1).all { it.closed })
            check(socket === f.sockets.last() && !socket.closed)
        },
        "cached UUID prioritises without filtering missing services" to {
            val f = Fixture(success = "false:$first")
            f.connector.connect(known, setOf(second))
            check(f.attempts == listOf("true:$second", "true:$first", "false:$second", "false:$first"))
        },
        "unrelated cached audio UUIDs never become connection targets" to {
            val f = Fixture()
            f.connector.connect(known, setOf(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")))
            check(f.attempts == listOf("true:$first", "true:$second", "false:$first", "false:$second", "legacy"))
        },
        "total failure closes every socket including channel 15" to {
            val f = Fixture()
            check(f.connector.connect(known) == null)
            check(f.sockets.size == 5 && f.sockets.all { it.closed })
            check(f.messages.count { "failed:" in it } == 5)
        },
        "legacy connection remains available after UUID failures" to {
            val f = Fixture(success = "legacy")
            check(f.connector.connect(known)?.label == "legacy")
            check(f.sockets.dropLast(1).all { it.closed } && !f.sockets.last().closed)
        },
        "creation and close failures do not prevent the next method" to {
            val f = Fixture(success = "false:$first", factoryFailure = "true:$first", closeFails = true)
            check(f.connector.connect(known)?.label == "false:$first")
            check(f.sockets.first().closed)
        },
        "reflection errors expose the underlying socket failure" to {
            val f = Fixture(failure = InvocationTargetException(IOException("underlying read failure")))
            f.connector.connect(known)
            check(f.messages.filter { "failed:" in it }.all { "IOException: underlying read failure" in it })
        },
        "permission denial closes the socket and aborts fallback" to {
            val f = Fixture(failure = SecurityException("permission denied"))
            val error = runCatching { f.connector.connect(known) }.exceptionOrNull()
            check(error is SecurityException)
            check(f.sockets.single().closed && f.attempts.size == 1)
        },
        "duplicate service records are tried once per authentication mode" to {
            val f = Fixture()
            f.connector.connect(listOf(first, first, second))
            check(f.attempts.size == 5)
        }
    )
    tests.forEach { (name, test) -> test(); println("PASS: $name") }
    println("${tests.size} RFCOMM regression checks passed")
}

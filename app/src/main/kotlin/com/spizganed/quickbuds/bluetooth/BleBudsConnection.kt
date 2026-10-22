package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.spizganed.quickbuds.protocol.OppoPacketFramer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Nord Buds 4 Pro's OPO GATT channel; wire UUID references are documented in PROTOCOL.md §1. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class BleBudsConnection(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onPacket: (ByteArray) -> Unit,
    private val onLost: () -> Unit
) {
    private val opoService = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
    private val writeUuid = UUID.fromString("0100079a-d102-11e1-9b23-00025b00a5a5")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val operationLock = Any()
    private val connectedSignal = CountDownLatch(1)
    private val scanSignal = CountDownLatch(1)
    private val framers = ConcurrentHashMap<UUID, OppoPacketFramer>()
    private val earlyPackets = mutableListOf<ByteArray>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var mtu = 23
    @Volatile private var writer: BluetoothGattCharacteristic? = null
    @Volatile private var scanCallback: ScanCallback? = null
    @Volatile private var operation: Operation? = null

    private class Operation(val kind: String) {
        val signal = CountDownLatch(1)
        @Volatile var status = BluetoothGatt.GATT_FAILURE
    }

    fun isConnected() = ready && connected && !closed
    fun maxWriteLength() = mtu - 3

    private fun complete(kind: String, status: Int) {
        operation?.takeIf { it.kind == kind }?.let { it.status = status; it.signal.countDown() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            // Android can invoke the callback before connectGatt returns its client object.
            if (gatt == null) gatt = connection
            if (connection !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connectedSignal.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val wasReady = ready
                connected = false
                ready = false
                connectedSignal.countDown()
                operation?.let { it.status = BluetoothGatt.GATT_FAILURE; it.signal.countDown() }
                log("BLE link closed: status=$status, state=$newState")
                if (wasReady) onLost()
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (connection === gatt && !closed) complete("services", status)
        }

        override fun onMtuChanged(connection: BluetoothGatt, value: Int, status: Int) {
            if (connection !== gatt || closed) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            complete("mtu", status)
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (connection === gatt && !closed) complete("descriptor", status)
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (connection === gatt && !closed) complete("write", status)
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (connection === gatt && !closed) receive(characteristic, value)
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && connection === gatt && !closed)
                characteristic.value?.let { receive(characteristic, it.copyOf()) }
        }
    }

    private fun receive(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val framer = framers.getOrPut(characteristic.uuid) {
            OppoPacketFramer().also { it.onDiscard = { data ->
                log("BLE discarded ${data.size} bytes outside an OPO frame")
            } }
        }
        // A characteristic's notifications form one stream; never interleave separate services.
        val packets = synchronized(framer) { framer.append(bytes, bytes.size) }
        for (packet in packets) {
            val deliver = synchronized(earlyPackets) {
                if (!ready) { earlyPackets += packet; false } else true
            }
            if (deliver) onPacket(packet)
        }
    }

    /** Worker thread only. Every GATT operation completes before the next one starts. */
    fun open(paired: BluetoothDevice) {
        val device = findPeripheral(paired)
        if (closed) throw IOException("BLE connection cancelled")
        log("Connecting to Nord Buds OPO service over BLE")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Android did not create a GATT connection")
        if (closed) {
            runCatching { gatt?.close() }
            gatt = null
            throw IOException("BLE connection cancelled")
        }
        if (!connectedSignal.await(10, TimeUnit.SECONDS) || !connected || closed)
            throw IOException("BLE connection failed or timed out")
        val connection = gatt ?: throw IOException("BLE connection closed")
        // Android 14+ may negotiate 517 regardless of the requested 247.
        try {
            perform("mtu", 2, closeOnTimeout = false) { connection.requestMtu(247) }
        } catch (e: IOException) {
            if (closed || !connected) throw e
            log("BLE MTU negotiation unavailable; retaining MTU=$mtu")
        }
        log("BLE negotiated MTU=$mtu")
        perform("services") { connection.discoverServices() }
        log("BLE services: ${connection.services.joinToString { it.uuid.toString() }}")
        writer = connection.getService(opoService)?.getCharacteristic(writeUuid)
            ?: throw IOException("The OPO BLE write characteristic is absent")
        if (writer!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)
            throw IOException("The OPO characteristic does not support writes without response")

        var subscriptions = 0
        // The Nord reference receives OPO replies on notifications across discovered services.
        // Only the known OPO writer ever receives application command bytes.
        for (service in connection.services) {
            for (characteristic in service.characteristics) {
                val notify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (!notify && !indicate) continue
                val descriptor = characteristic.getDescriptor(cccdUuid) ?: continue
                if (!connection.setCharacteristicNotification(characteristic, true)) continue
                val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                try {
                    perform("descriptor") {
                        if (Build.VERSION.SDK_INT >= 33) connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                        else { descriptor.value = value; connection.writeDescriptor(descriptor) }
                    }
                    subscriptions++
                    log("BLE subscribed: ${characteristic.uuid}")
                } catch (e: IOException) {
                    if (closed || !connected) throw e
                    log("BLE notification subscription refused: ${characteristic.uuid}: ${e.message}")
                }
            }
        }
        if (subscriptions == 0) throw IOException("No OPO/FE2C notifications could be enabled")
        val buffered = synchronized(earlyPackets) {
            ready = true
            earlyPackets.toList().also { earlyPackets.clear() }
        }
        log("BLE control channel ready; $subscriptions notification subscriptions")
        buffered.forEach(onPacket)
    }

    private fun findPeripheral(paired: BluetoothDevice): BluetoothDevice {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val name = paired.name ?: return paired
        val existing = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
            .firstOrNull { it.name == name }
        if (existing != null) return existing
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return paired
        val found = AtomicReference<BluetoothDevice?>()
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.deviceName == name || result.device.name == name) {
                    found.compareAndSet(null, result.device)
                    scanSignal.countDown()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                log("BLE scan failed: $errorCode")
                scanSignal.countDown()
            }
        }
        scanCallback = scan
        try {
            log("Looking for the Nord Buds BLE advertisement")
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(name).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            scanSignal.await(8, TimeUnit.SECONDS)
        } finally {
            scanner.stopScan(scan)
            scanCallback = null
        }
        if (found.get() == null) log("No named BLE advertisement; trying the paired device's LE identity")
        return found.get() ?: paired
    }

    private fun perform(kind: String, timeoutSeconds: Long = 6, closeOnTimeout: Boolean = true,
        start: () -> Boolean) = synchronized(operationLock) {
        if (closed || !connected) throw IOException("BLE link is closed")
        val pending = Operation(kind)
        operation = pending
        try {
            if (!start()) throw IOException("Android refused BLE $kind")
            if (!pending.signal.await(timeoutSeconds, TimeUnit.SECONDS)) {
                // A late callback must never satisfy a subsequent write or CCCD operation.
                if (closeOnTimeout) close()
                throw IOException("BLE $kind timed out")
            }
            if (closed || pending.status != BluetoothGatt.GATT_SUCCESS)
                throw IOException("BLE $kind failed: status=${pending.status}")
        } finally { operation = null }
    }

    fun write(bytes: ByteArray) {
        val connection = gatt ?: throw IOException("BLE is not connected")
        val characteristic = writer ?: throw IOException("BLE write characteristic is not ready")
        if (bytes.size > mtu - 3) throw IOException("Packet exceeds negotiated BLE MTU; not sent")
        val wasReady = ready
        try {
            perform("write") {
                if (Build.VERSION.SDK_INT >= 33)
                    connection.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    characteristic.value = bytes
                    connection.writeCharacteristic(characteristic)
                }
            }
        } catch (e: IOException) {
            close()
            if (wasReady) onLost()
            throw e
        }
    }

    fun close() {
        closed = true
        ready = false
        connected = false
        scanSignal.countDown()
        connectedSignal.countDown()
        operation?.signal?.countDown()
        scanCallback?.let { scan -> runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scan)
        } }
        val connection = gatt
        gatt = null
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
    }
}

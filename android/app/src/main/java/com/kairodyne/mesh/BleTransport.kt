package com.kairodyne.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Broadcast-style frame transport over Bluetooth LE GATT. Every phone advertises one service with one writable
 * characteristic and also connects to every phone it sees; `send` writes the frame to all connected peers.
 * Each connection carries one GATT operation at a time, so frames wait in a bounded per-peer queue.
 */
@SuppressLint("MissingPermission") // MainActivity obtains the Bluetooth permissions before starting this
class BleTransport(private val context: Context, private val onFrame: (ByteArray) -> Unit) {
    private class Peer(val gatt: BluetoothGatt) {
        var characteristic: BluetoothGattCharacteristic? = null
        val queue = ArrayDeque<ByteArray>()
        var busy = false
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val peers = HashMap<String, Peer>()
    private var server: BluetoothGattServer? = null

    val peerCount get() = synchronized(peers) { peers.values.count { it.characteristic != null } }

    fun start() {
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(FRAME,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        server = manager.openGattServer(context, serverCallback).apply { addService(service) }

        adapter.bluetoothLeAdvertiser.startAdvertising(
            AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),
            advertiseCallback)
        adapter.bluetoothLeScanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
            scanCallback)
        Log.i(TAG, "transport started")
    }

    fun stop() {
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        synchronized(peers) { peers.values.forEach { it.gatt.close() }; peers.clear() }
        server?.close()
    }

    fun send(frame: ByteArray) {
        synchronized(peers) {
            for (peer in peers.values) {
                if (peer.characteristic == null) continue
                if (peer.queue.size >= MAX_QUEUE) peer.queue.removeFirst() // the fountain code tolerates loss
                peer.queue.addLast(frame)
                pump(peer)
            }
        }
    }

    private fun pump(peer: Peer) {
        val c = peer.characteristic ?: return
        if (peer.busy) return
        val frame = peer.queue.removeFirstOrNull() ?: return
        peer.busy = peer.gatt.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
            BluetoothStatusCodes.SUCCESS
        if (!peer.busy) peer.queue.addFirst(frame) // the stack is busy: retried on the next send
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            synchronized(peers) {
                if (address in peers) return
                peers[address] = Peer(result.device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE))
            }
            Log.i(TAG, "connecting $address")
        }
        override fun onScanFailed(errorCode: Int) = Log.w(TAG, "scan failed $errorCode").let {}
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.requestMtu(517)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(peers) { peers.remove(gatt.device.address) }
                gatt.close()
                Log.i(TAG, "lost ${gatt.device.address}")
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (mtu < MIN_MTU) { Log.w(TAG, "mtu $mtu too small for ${gatt.device.address}"); gatt.disconnect(); return }
            gatt.discoverServices()
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val c = gatt.getService(SERVICE)?.getCharacteristic(FRAME)
            if (c == null) { gatt.disconnect(); return }
            synchronized(peers) { peers[gatt.device.address]?.characteristic = c }
            Log.i(TAG, "peer ready ${gatt.device.address}")
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(peers) { peers[gatt.device.address]?.let { it.busy = false; pump(it) } }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (!preparedWrite && offset == 0) onFrame(value)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = Log.w(TAG, "advertise failed $errorCode").let {}
    }

    companion object {
        const val TAG = "Kairo"
        val SERVICE: UUID = UUID.fromString("6b616972-6f00-4d65-7368-000000000001")
        val FRAME: UUID = UUID.fromString("6b616972-6f00-4d65-7368-000000000002")
        private const val MIN_MTU = 467 // largest frame (464 bytes) plus the 3-byte ATT header
        private const val MAX_QUEUE = 600
    }
}

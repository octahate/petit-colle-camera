package com.petitcolle.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.UUID

/**
 * BLE transport for the AiYin D11s sold as the Fichero label printer.
 *
 * Protocol origin and command reference:
 * https://github.com/0xMH/fichero-printer/blob/main/docs/PROTOCOL.md
 */
class FicheroPrinter(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var scanCount = 0

    @SuppressLint("MissingPermission")
    fun scanAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        ) {
            onStatus("Bluetooth permission is needed")
            return
        }
        if (writer != null && gatt != null) {
            onStatus("Fichero ready")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onStatus("Bluetooth is unavailable")
            return
        }
        if (scanning) return
        scanning = true
        scanCount = 0
        onStatus("Looking for Fichero…")
        try {
            scanner.startScan(scanCallback)
        } catch (_: SecurityException) {
            scanning = false
            onStatus("Bluetooth permission is needed")
            return
        }
        handler.postDelayed({
            if (scanning) {
                scanner.stopScan(scanCallback)
                scanning = false
                onStatus("No Fichero found — scanned ${scanCount} nearby devices")
            }
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    fun print(frame: ThermalFrame, density: Int = 1) {
        val characteristic = writer
        val activeGatt = gatt
        if (characteristic == null || activeGatt == null) {
            onStatus("Connect the Fichero first")
            return
        }
        val packets = mutableListOf<ByteArray>()
        packets += byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, density.toByte())
        packets += byteArrayOf(0x10, 0xFF.toByte(), 0x84.toByte(), 0x00)
        packets += ByteArray(12)
        packets += byteArrayOf(0x10, 0xFF.toByte(), 0xFE.toByte(), 0x01)
        packets += byteArrayOf(0x1D, 0x76, 0x30, 0x00, 0x0C, 0x00, 0xC0.toByte(), 0x00)
        val raster = packRows(frame)
        raster.asList().chunked(20).forEach { packets += it.toByteArray() }
        packets += byteArrayOf(0x1D, 0x0C)
        packets += byteArrayOf(0x10, 0xFF.toByte(), 0xFE.toByte(), 0x45)
        onStatus("Printing the frame you saw…")
        sendPackets(activeGatt, characteristic, packets, 0)
    }

    private fun packRows(frame: ThermalFrame): ByteArray {
        val result = ByteArray(PRINT_HEIGHT * 12)
        for (y in 0 until PRINT_HEIGHT) for (byteIndex in 0 until 12) {
            var value = 0
            for (bit in 0 until 8) {
                if (frame.dots[y * PRINT_WIDTH + byteIndex * 8 + bit].toInt() == 1) value = value or (0x80 shr bit)
            }
            result[y * 12 + byteIndex] = value.toByte()
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun sendPackets(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        packets: List<ByteArray>,
        index: Int,
    ) {
        if (index >= packets.size) {
            onStatus("Sent — the sticker should be printing")
            return
        }
        if (!writeCharacteristic(activeGatt, characteristic, packets[index])) {
            onStatus("Printer connection was interrupted")
            return
        }
        handler.postDelayed({ sendPackets(activeGatt, characteristic, packets, index + 1) }, 18)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeCharacteristic(
            characteristic,
            value,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        ) == BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = value
        activeGatt.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptor(
        activeGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        descriptor.value = value
        activeGatt.writeDescriptor(descriptor)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        handler.removeCallbacksAndMessages(null)
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writer = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "(unnamed)"
            scanCount += 1
            val normalizedName = name.uppercase(Locale.US)
            if (!normalizedName.contains("FICHERO") && !normalizedName.contains("D11S") && !normalizedName.contains("AIYIN")) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            scanning = false
            onStatus("Connecting to $name…")
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus("Bluetooth scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothGatt.STATE_DISCONNECTED) {
                writer = null
                gatt.close()
                if (this@FicheroPrinter.gatt === gatt) this@FicheroPrinter.gatt = null
                onStatus("Printer disconnected")
                return
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                onStatus("Checking printer…")
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_F0) ?: gatt.getService(SERVICE_FF)
            writer = service?.getCharacteristic(if (service.uuid == SERVICE_F0) WRITE_F0 else WRITE_FF)
            val notifier = service?.getCharacteristic(if (service.uuid == SERVICE_F0) NOTIFY_F0 else NOTIFY_FF)
            if (writer == null) {
                onStatus("This is not a supported Fichero")
                return
            }
            if (notifier != null) {
                gatt.setCharacteristicNotification(notifier, true)
                notifier.getDescriptor(CCCD)?.let { descriptor ->
                    writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            onStatus("Fichero ready")
        }
    }

    private companion object {
        val SERVICE_F0: UUID = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val WRITE_F0: UUID = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_F0: UUID = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val SERVICE_FF: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val WRITE_FF: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val NOTIFY_FF: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

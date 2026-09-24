package com.petitcolle.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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

data class PrinterTelemetry(
    val batteryPercent: Int? = null,
    val printing: Boolean = false,
    val coverOpen: Boolean = false,
    val outOfPaper: Boolean = false,
    val lowBattery: Boolean = false,
    val charging: Boolean = false,
    val overheated: Boolean = false,
)

/**
 * BLE transport for the AiYin D11s sold as the Fichero label printer.
 *
 * Protocol origin and command reference:
 * https://github.com/0xMH/fichero-printer/blob/main/docs/PROTOCOL.md
 */
class FicheroPrinter(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTelemetry: (PrinterTelemetry) -> Unit,
) {
    private enum class InfoQuery { STATUS, BATTERY }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var scanCount = 0
    private var printing = false
    private var telemetryStarted = false
    private var pendingQuery: InfoQuery? = null
    private var telemetry = PrinterTelemetry()

    private val telemetryPoll = Runnable { queryStatus() }
    private val queryTimeout = Runnable {
        pendingQuery = null
        scheduleTelemetryPoll(TELEMETRY_INTERVAL_MS)
    }

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
            scheduleTelemetryPoll(0L)
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
        printing = true
        pendingQuery = null
        handler.removeCallbacks(telemetryPoll)
        handler.removeCallbacks(queryTimeout)
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
            printing = false
            onStatus("Sent — the sticker should be printing")
            scheduleTelemetryPoll(800L)
            return
        }
        if (!writeCharacteristic(activeGatt, characteristic, packets[index])) {
            printing = false
            onStatus("Printer connection was interrupted")
            scheduleTelemetryPoll(TELEMETRY_INTERVAL_MS)
            return
        }
        handler.postDelayed({ sendPackets(activeGatt, characteristic, packets, index + 1) }, 18)
    }

    private fun startTelemetry() {
        if (telemetryStarted) return
        telemetryStarted = true
        scheduleTelemetryPoll(120L)
    }

    private fun scheduleTelemetryPoll(delayMs: Long) {
        handler.removeCallbacks(telemetryPoll)
        if (writer != null && !printing) handler.postDelayed(telemetryPoll, delayMs)
    }

    private fun queryStatus() {
        if (printing || pendingQuery != null) return
        if (sendInfoQuery(InfoQuery.STATUS, GET_STATUS)) return
        scheduleTelemetryPoll(TELEMETRY_INTERVAL_MS)
    }

    private fun queryBattery() {
        if (!sendInfoQuery(InfoQuery.BATTERY, GET_BATTERY)) {
            scheduleTelemetryPoll(TELEMETRY_INTERVAL_MS)
        }
    }

    private fun sendInfoQuery(query: InfoQuery, command: ByteArray): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = writer ?: return false
        pendingQuery = query
        if (!writeCharacteristic(activeGatt, characteristic, command)) {
            pendingQuery = null
            return false
        }
        handler.removeCallbacks(queryTimeout)
        handler.postDelayed(queryTimeout, QUERY_TIMEOUT_MS)
        return true
    }

    private fun handleNotification(value: ByteArray) {
        if (value.isEmpty()) return
        if (value.size >= 2 && value[0].toInt() and 0xFF == 0xFF) {
            pendingQuery = null
            handler.removeCallbacks(queryTimeout)
            updateErrorMask(value[1].toInt() and 0xFF)
            scheduleTelemetryPoll(TELEMETRY_INTERVAL_MS)
            return
        }
        when (pendingQuery) {
            InfoQuery.STATUS -> {
                pendingQuery = null
                handler.removeCallbacks(queryTimeout)
                updateStatusMask(value[0].toInt() and 0xFF)
                handler.postDelayed({ if (!printing) queryBattery() }, QUERY_GAP_MS)
            }
            InfoQuery.BATTERY -> {
                pendingQuery = null
                handler.removeCallbacks(queryTimeout)
                if (value.size >= 2) updateTelemetry(telemetry.copy(batteryPercent = (value[1].toInt() and 0xFF).coerceIn(0, 100)))
                scheduleTelemetryPoll(TELEMETRY_INTERVAL_MS)
            }
            null -> Unit
        }
    }

    private fun updateStatusMask(mask: Int) {
        updateTelemetry(
            telemetry.copy(
                printing = mask and 0x01 != 0,
                coverOpen = mask and 0x02 != 0,
                outOfPaper = mask and 0x04 != 0,
                lowBattery = mask and 0x08 != 0,
                charging = mask and 0x20 != 0,
                overheated = mask and 0x50 != 0,
            ),
        )
    }

    private fun updateErrorMask(mask: Int) {
        updateTelemetry(
            telemetry.copy(
                overheated = mask and 0x01 != 0,
                coverOpen = mask and 0x02 != 0,
                outOfPaper = mask and 0x04 != 0,
                lowBattery = mask and 0x08 != 0,
            ),
        )
    }

    private fun updateTelemetry(value: PrinterTelemetry) {
        if (value == telemetry) return
        telemetry = value
        onTelemetry(value)
    }

    private fun clearTelemetry() {
        telemetryStarted = false
        pendingQuery = null
        printing = false
        handler.removeCallbacks(telemetryPoll)
        handler.removeCallbacks(queryTimeout)
        updateTelemetry(PrinterTelemetry())
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
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
        clearTelemetry()
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
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                writer = null
                clearTelemetry()
                gatt.close()
                if (this@FicheroPrinter.gatt === gatt) this@FicheroPrinter.gatt = null
                onStatus("Printer disconnected")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
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
            if (notifier != null && gatt.setCharacteristicNotification(notifier, true)) {
                val descriptor = notifier.getDescriptor(CCCD)
                if (descriptor == null || !writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    startTelemetry()
                }
            }
            onStatus("Fichero ready")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) startTelemetry()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.value ?: return)
        }
    }

    private companion object {
        const val TELEMETRY_INTERVAL_MS = 10_000L
        const val QUERY_TIMEOUT_MS = 1_500L
        const val QUERY_GAP_MS = 80L
        val GET_STATUS = byteArrayOf(0x10, 0xFF.toByte(), 0x40)
        val GET_BATTERY = byteArrayOf(0x10, 0xFF.toByte(), 0x50, 0xF1.toByte())
        val SERVICE_F0: UUID = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val WRITE_F0: UUID = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_F0: UUID = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val SERVICE_FF: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val WRITE_FF: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val NOTIFY_FF: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

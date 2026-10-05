package com.refacecslive.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.refacecslive.protocol.RefaceProtocol
import com.refacecslive.protocol.SysExFramer
import java.io.Closeable
import java.io.IOException

class RefaceMidiClient(
    context: Context,
    private val onStatus: (Status) -> Unit,
    private val onDevicesChanged: (List<DeviceOption>, Int?) -> Unit
) : Closeable {
    data class Status(
        val connected: Boolean,
        val title: String,
        val detail: String = ""
    )

    data class DeviceOption(
        val id: Int,
        val name: String,
        val description: String
    )

    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val preferences = context.getSharedPreferences("midi_device_selection", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var selectedDeviceKey: String? = preferences.getString(KEY_SELECTED_DEVICE, null)
    private var device: MidiDevice? = null
    private var inputPort: MidiInputPort? = null
    private var outputPort: MidiOutputPort? = null
    private var openingDeviceId: Int? = null
    private var connectionGeneration = 0

    private var captureCallback: ((Result<RefaceProtocol.PatchDump>) -> Unit)? = null
    private val captureFrames = mutableListOf<ByteArray>()

    private val framer = SysExFramer { frame ->
        handler.post { receiveSysEx(frame) }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            framer.accept(msg, offset, count)
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            refreshDeviceOptions()
            if (device == null && openingDeviceId == null) scanAndConnect()
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id || openingDeviceId == info.id) {
                disconnect("MIDI device disconnected")
                scanAndConnect()
            } else {
                refreshDeviceOptions()
            }
        }

        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = Unit
    }

    init {
        manager.registerDeviceCallback(deviceCallback, handler)
        scanAndConnect()
    }

    fun scanAndConnect() {
        val candidates = refreshDeviceOptions()
        if (device != null || openingDeviceId != null) return

        val remembered = selectedDeviceKey?.let { key ->
            candidates.firstOrNull { deviceKey(it) == key }
        }
        val best = remembered
            ?: if (selectedDeviceKey == null) {
                candidates.firstOrNull { deviceScore(it) > USB_SCORE }
                    ?: candidates.singleOrNull()
            } else {
                null
            }

        if (best == null) {
            onStatus(
                Status(
                    connected = false,
                    title = when {
                        candidates.isEmpty() -> "No MIDI device connected"
                        selectedDeviceKey != null -> "Selected MIDI device unavailable"
                        else -> "Choose a MIDI device"
                    },
                    detail = when {
                        candidates.isEmpty() -> "Connect USB-C/OTG to the reface USB TO HOST port"
                        selectedDeviceKey != null -> "Select another device or reconnect the saved device"
                        else -> "More than one bidirectional MIDI device is available"
                    }
                )
            )
            return
        }
        open(best)
    }

    fun selectDevice(deviceId: Int) {
        val selected = midiCandidates().firstOrNull { it.id == deviceId }
        if (selected == null) {
            refreshDeviceOptions()
            onStatus(Status(false, "MIDI device unavailable", "Scan and choose a connected device"))
            return
        }

        selectedDeviceKey = deviceKey(selected)
        preferences.edit().putString(KEY_SELECTED_DEVICE, selectedDeviceKey).apply()
        refreshDeviceOptions()

        if (device?.info?.id == selected.id) return
        disconnect("Switching MIDI device")
        open(selected)
    }

    fun captureCurrentPatch(callback: (Result<RefaceProtocol.PatchDump>) -> Unit) {
        val sendPort = inputPort
        if (sendPort == null) {
            callback(Result.failure(IOException("Reface CS is not connected")))
            return
        }
        if (captureCallback != null) {
            callback(Result.failure(IOException("A capture is already in progress")))
            return
        }

        captureFrames.clear()
        framer.reset()
        captureCallback = callback
        handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
        try {
            sendPort.send(
                RefaceProtocol.toneGeneratorDumpRequest,
                0,
                RefaceProtocol.toneGeneratorDumpRequest.size
            )
        } catch (error: IOException) {
            finishCapture(Result.failure(error))
        }
    }

    fun sendPatch(
        sysex: ByteArray,
        sendAllNotesOff: Boolean = true,
        callback: (Result<Unit>) -> Unit
    ) {
        val sendPort = inputPort
        if (sendPort == null) {
            callback(Result.failure(IOException("Reface CS is not connected")))
            return
        }

        val messages = try {
            val normalized = RefaceProtocol.parsePatchDump(sysex).sysex
            RefaceProtocol.splitMessages(normalized)
        } catch (error: Exception) {
            callback(Result.failure(error))
            return
        }

        try {
            if (sendAllNotesOff) {
                // The CS receive channel is a global setting and may not be channel 1.
                for (channel in 0..15) {
                    val allNotesOff = byteArrayOf((0xB0 or channel).toByte(), 123, 0)
                    sendPort.send(allNotesOff, 0, allNotesOff.size)
                }
            }
        } catch (error: IOException) {
            callback(Result.failure(error))
            return
        }

        fun sendAt(index: Int) {
            if (index >= messages.size) {
                callback(Result.success(Unit))
                return
            }
            try {
                val message = messages[index]
                sendPort.send(message, 0, message.size)
                handler.postDelayed({ sendAt(index + 1) }, BULK_MESSAGE_GAP_MS)
            } catch (error: IOException) {
                callback(Result.failure(error))
            }
        }
        sendAt(0)
    }

    private fun open(info: MidiDeviceInfo) {
        selectedDeviceKey = deviceKey(info)
        preferences.edit().putString(KEY_SELECTED_DEVICE, selectedDeviceKey).apply()
        refreshDeviceOptions()

        openingDeviceId = info.id
        val generation = ++connectionGeneration
        onStatus(Status(false, "Connecting…", deviceName(info)))
        manager.openDevice(info, { opened ->
            openingDeviceId = null
            if (generation != connectionGeneration) {
                opened?.close()
                return@openDevice
            }
            if (opened == null) {
                onStatus(Status(false, "Could not open MIDI device", deviceName(info)))
                return@openDevice
            }

            val sendPort = opened.openInputPort(0)
            val receivePort = opened.openOutputPort(0)
            if (sendPort == null || receivePort == null) {
                sendPort?.close()
                receivePort?.close()
                opened.close()
                onStatus(Status(false, "MIDI ports unavailable", deviceName(info)))
                return@openDevice
            }

            device = opened
            inputPort = sendPort
            outputPort = receivePort
            receivePort.connect(receiver)
            refreshDeviceOptions()
            onStatus(Status(true, "Connected", deviceName(info)))
        }, handler)
    }

    private fun receiveSysEx(frame: ByteArray) {
        if (captureCallback == null) return

        try {
            RefaceProtocol.validateBulkFrame(frame)
        } catch (_: Exception) {
            return
        }

        if (isAddress(frame, 0x0E, 0x0F, 0x00)) captureFrames.clear()
        captureFrames.add(frame)

        if (isAddress(frame, 0x0F, 0x0F, 0x00)) {
            val stream = captureFrames.fold(ByteArray(0)) { result, bytes -> result + bytes }
            try {
                finishCapture(Result.success(RefaceProtocol.parsePatchDump(stream)))
            } catch (error: Exception) {
                finishCapture(Result.failure(error))
            }
        }
    }

    private fun finishCapture(result: Result<RefaceProtocol.PatchDump>) {
        handler.removeCallbacks(captureTimeout)
        val callback = captureCallback ?: return
        captureCallback = null
        captureFrames.clear()
        callback(result)
    }

    private val captureTimeout = Runnable {
        finishCapture(Result.failure(IOException("Timed out waiting for the reface CS dump")))
    }

    private fun isAddress(frame: ByteArray, high: Int, mid: Int, low: Int): Boolean =
        frame.size > 10 &&
            (frame[8].toInt() and 0xFF) == high &&
            (frame[9].toInt() and 0xFF) == mid &&
            (frame[10].toInt() and 0xFF) == low

    private fun midiCandidates(): List<MidiDeviceInfo> = manager.devices
        .filter { it.inputPortCount > 0 && it.outputPortCount > 0 }
        .sortedWith(compareByDescending<MidiDeviceInfo>(::deviceScore).thenBy(::deviceName))

    private fun refreshDeviceOptions(): List<MidiDeviceInfo> {
        val candidates = midiCandidates()
        val selectedId = selectedDeviceKey?.let { key ->
            candidates.firstOrNull { deviceKey(it) == key }?.id
        }
        onDevicesChanged(
            candidates.map { info ->
                DeviceOption(
                    id = info.id,
                    name = deviceName(info),
                    description = "${deviceTransport(info)} · ${info.inputPortCount} in / ${info.outputPortCount} out"
                )
            },
            selectedId
        )
        return candidates
    }

    private fun deviceScore(info: MidiDeviceInfo): Int {
        val text = listOf(
            info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
            info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        ).filterNotNull().joinToString(" ").lowercase()

        var score = 0
        if ("reface cs" in text) score += 100
        if ("reface" in text) score += 40
        if ("yamaha" in text) score += 20
        if (info.type == MidiDeviceInfo.TYPE_USB) score += USB_SCORE
        return score
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val product = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        val manufacturer = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        return product ?: name ?: listOfNotNull(manufacturer, "MIDI device").joinToString(" ")
    }

    private fun deviceTransport(info: MidiDeviceInfo): String = when (info.type) {
        MidiDeviceInfo.TYPE_USB -> "USB"
        MidiDeviceInfo.TYPE_BLUETOOTH -> "Bluetooth"
        MidiDeviceInfo.TYPE_VIRTUAL -> "Virtual"
        else -> "MIDI"
    }

    private fun deviceKey(info: MidiDeviceInfo): String = listOf(
        info.type.toString(),
        info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty(),
        info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty(),
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty(),
        info.properties.getString("serial_number").orEmpty(),
        info.inputPortCount.toString(),
        info.outputPortCount.toString()
    ).joinToString("|")

    private fun disconnect(reason: String) {
        connectionGeneration++
        openingDeviceId = null
        captureCallback?.let {
            finishCapture(Result.failure(IOException(reason)))
        }
        try {
            outputPort?.disconnect(receiver)
        } catch (_: Exception) {
        }
        outputPort?.close()
        inputPort?.close()
        device?.close()
        outputPort = null
        inputPort = null
        device = null
        framer.reset()
        refreshDeviceOptions()
        onStatus(Status(false, "Disconnected", reason))
    }

    override fun close() {
        manager.unregisterDeviceCallback(deviceCallback)
        disconnect("MIDI connection closed")
    }

    private companion object {
        const val KEY_SELECTED_DEVICE = "selected_device_key"
        const val CAPTURE_TIMEOUT_MS = 2500L
        const val BULK_MESSAGE_GAP_MS = 12L
        const val USB_SCORE = 5
    }
}

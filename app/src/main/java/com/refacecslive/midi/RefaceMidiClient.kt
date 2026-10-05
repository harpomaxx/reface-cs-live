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
    private val onStatus: (Status) -> Unit
) : Closeable {
    data class Status(
        val connected: Boolean,
        val title: String,
        val detail: String = ""
    )

    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val handler = Handler(Looper.getMainLooper())
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
            if (device == null) scanAndConnect()
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id || openingDeviceId == info.id) {
                disconnect("USB MIDI device disconnected")
                scanAndConnect()
            }
        }

        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = Unit
    }

    init {
        manager.registerDeviceCallback(deviceCallback, handler)
        scanAndConnect()
    }

    fun scanAndConnect() {
        if (device != null || openingDeviceId != null) return
        val candidates = manager.devices
            .filter { it.inputPortCount > 0 && it.outputPortCount > 0 }
            .sortedByDescending(::deviceScore)

        val best = candidates.firstOrNull { deviceScore(it) > 0 }
            ?: candidates.singleOrNull()

        if (best == null) {
            onStatus(
                Status(
                    connected = false,
                    title = "Reface not connected",
                    detail = if (candidates.isEmpty()) {
                        "Connect USB-C/OTG to the reface USB TO HOST port"
                    } else {
                        "No Yamaha reface CS found"
                    }
                )
            )
            return
        }
        open(best)
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
        if (info.type == MidiDeviceInfo.TYPE_USB) score += 5
        return score
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val product = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        val manufacturer = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        return product ?: name ?: listOfNotNull(manufacturer, "USB MIDI").joinToString(" ")
    }

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
        onStatus(Status(false, "Disconnected", reason))
    }

    override fun close() {
        manager.unregisterDeviceCallback(deviceCallback)
        disconnect("MIDI connection closed")
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 2500L
        const val BULK_MESSAGE_GAP_MS = 12L
    }
}

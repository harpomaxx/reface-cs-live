package com.refacecslive.protocol

object RefaceProtocol {
    private const val YAMAHA = 0x43
    private const val GROUP_HIGH = 0x7F
    private const val GROUP_LOW = 0x1C
    private const val MODEL_CS = 0x03

    private val HEADER_ADDRESS = Address(0x0E, 0x0F, 0x00)
    private val TONE_ADDRESS = Address(0x30, 0x00, 0x00)
    private val FOOTER_ADDRESS = Address(0x0F, 0x0F, 0x00)

    /** Requests the current tone-generator settings from Yamaha device number 1. */
    val toneGeneratorDumpRequest: ByteArray = bytes(
        0xF0, 0x43, 0x20, 0x7F, 0x1C, 0x03, 0x0E, 0x0F, 0x00, 0xF7
    )

    data class Address(val high: Int, val mid: Int, val low: Int)

    data class VoiceParameters(
        val volume: Int,
        val lfoAssign: Int,
        val lfoDepth: Int,
        val lfoSpeed: Int,
        val portamento: Int,
        val oscillatorType: Int,
        val oscillatorTexture: Int,
        val oscillatorModulation: Int,
        val filterCutoff: Int,
        val filterResonance: Int,
        val envelopeBalance: Int,
        val attack: Int,
        val decay: Int,
        val sustain: Int,
        val release: Int,
        val effectType: Int,
        val effectDepth: Int,
        val effectRate: Int
    ) {
        val oscillatorName: String
            get() = listOf("Multi-saw", "Pulse", "Sync", "Ring", "FM")
                .getOrElse(oscillatorType) { "Unknown" }

        val effectName: String
            get() = listOf("Distortion", "Chorus/Flanger", "Phaser", "Delay", "Off")
                .getOrElse(effectType) { "Unknown" }

        val lfoAssignmentName: String
            get() = listOf("Off", "Amp", "Filter", "Pitch", "Oscillator")
                .getOrElse(lfoAssign) { "Unknown" }

        fun summary(): String = "$oscillatorName · $effectName · Cutoff $filterCutoff"
    }

    data class PatchDump(
        val sysex: ByteArray,
        val toneData: ByteArray,
        val parameters: VoiceParameters
    )

    class InvalidDumpException(message: String) : IllegalArgumentException(message)

    fun splitMessages(stream: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        SysExFramer(onMessage = messages::add).accept(stream)
        return messages
    }

    /**
     * Parses a standard 61-byte reface CS patch dump. A lone tone-data block is
     * also accepted; the required bulk header and footer are generated for it.
     */
    fun parsePatchDump(stream: ByteArray): PatchDump {
        val messages = splitMessages(stream)
        if (messages.isEmpty()) throw InvalidDumpException("No SysEx messages found")

        val validBlocks = messages.mapNotNull { message ->
            try {
                validateBulkFrame(message)
                message to addressOf(message)
            } catch (_: InvalidDumpException) {
                null
            }
        }

        val tone = validBlocks.firstOrNull { it.second == TONE_ADDRESS }?.first
            ?: throw InvalidDumpException("No reface CS tone-generator block found")
        val toneData = payloadOf(tone)
        if (toneData.size != 22) {
            throw InvalidDumpException("Expected 22 tone-generator bytes, found ${toneData.size}")
        }
        validateToneRanges(toneData)

        val deviceNumber = unsigned(tone[2]) and 0x0F
        val header = validBlocks.firstOrNull { it.second == HEADER_ADDRESS }?.first
            ?: makeBulkFrame(deviceNumber, HEADER_ADDRESS, byteArrayOf())
        val footer = validBlocks.firstOrNull { it.second == FOOTER_ADDRESS }?.first
            ?: makeBulkFrame(deviceNumber, FOOTER_ADDRESS, byteArrayOf())

        val normalized = header + tone + footer
        return PatchDump(normalized, toneData, parseParameters(toneData))
    }

    fun validateBulkFrame(frame: ByteArray) {
        if (frame.size < 13 || unsigned(frame.first()) != 0xF0 || unsigned(frame.last()) != 0xF7) {
            throw InvalidDumpException("Invalid SysEx framing")
        }
        if (unsigned(frame[1]) != YAMAHA ||
            unsigned(frame[3]) != GROUP_HIGH ||
            unsigned(frame[4]) != GROUP_LOW ||
            unsigned(frame[7]) != MODEL_CS
        ) {
            throw InvalidDumpException("Not a Yamaha reface CS bulk block")
        }
        if ((unsigned(frame[2]) and 0xF0) != 0) {
            throw InvalidDumpException("Not a bulk-dump response")
        }

        val byteCount = unsigned(frame[5]) * 128 + unsigned(frame[6])
        if (frame.size != byteCount + 9) {
            throw InvalidDumpException("Bulk byte count does not match message length")
        }
        if (checksum(frame, 7, frame.size - 2) != unsigned(frame[frame.size - 2])) {
            throw InvalidDumpException("Invalid Yamaha checksum")
        }
    }

    fun parseParameters(data: ByteArray): VoiceParameters {
        if (data.size != 22) throw InvalidDumpException("Tone data must be 22 bytes")
        fun at(index: Int) = unsigned(data[index])
        return VoiceParameters(
            volume = at(0),
            lfoAssign = at(2),
            lfoDepth = at(3),
            lfoSpeed = at(4),
            portamento = at(5),
            oscillatorType = at(6),
            oscillatorTexture = at(7),
            oscillatorModulation = at(8),
            filterCutoff = at(9),
            filterResonance = at(10),
            envelopeBalance = at(11),
            attack = at(12),
            decay = at(13),
            sustain = at(14),
            release = at(15),
            effectType = at(16),
            effectDepth = at(17),
            effectRate = at(18)
        )
    }

    private fun validateToneRanges(data: ByteArray) {
        val p = parseParameters(data)
        if (p.lfoAssign !in 0..4) throw InvalidDumpException("Invalid LFO assignment")
        if (p.oscillatorType !in 0..4) throw InvalidDumpException("Invalid oscillator type")
        if (p.effectType !in 0..4) throw InvalidDumpException("Invalid effect type")
        if (data.any { unsigned(it) > 0x7F }) throw InvalidDumpException("Tone data is not 7-bit MIDI data")
    }

    private fun addressOf(frame: ByteArray) = Address(
        unsigned(frame[8]), unsigned(frame[9]), unsigned(frame[10])
    )

    private fun payloadOf(frame: ByteArray): ByteArray = frame.copyOfRange(11, frame.size - 2)

    private fun makeBulkFrame(deviceNumber: Int, address: Address, data: ByteArray): ByteArray {
        val count = 4 + data.size // model ID + three address bytes + data
        val body = byteArrayOf(
            MODEL_CS.toByte(), address.high.toByte(), address.mid.toByte(), address.low.toByte()
        ) + data
        val check = (-body.sumOf { unsigned(it) }) and 0x7F
        return bytes(
            0xF0, YAMAHA, deviceNumber and 0x0F, GROUP_HIGH, GROUP_LOW,
            (count shr 7) and 0x7F, count and 0x7F
        ) + body + bytes(check, 0xF7)
    }

    private fun checksum(frame: ByteArray, start: Int, checksumIndex: Int): Int {
        val sum = (start until checksumIndex).sumOf { unsigned(frame[it]) }
        return (-sum) and 0x7F
    }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

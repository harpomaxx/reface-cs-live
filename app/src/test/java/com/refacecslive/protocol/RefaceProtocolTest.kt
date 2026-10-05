package com.refacecslive.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefaceProtocolTest {
    @Test
    fun `framer reassembles fragments and ignores active sensing`() {
        val messages = mutableListOf<ByteArray>()
        val framer = SysExFramer(onMessage = messages::add)

        framer.accept(bytes(0xFE, 0xF0, 0x43, 0x00))
        framer.accept(bytes(0xFE, 0x7F, 0xF7, 0xFE))

        assertEquals(1, messages.size)
        assertArrayEquals(bytes(0xF0, 0x43, 0x00, 0x7F, 0xF7), messages.single())
    }

    @Test
    fun `parses a complete 61 byte CS patch`() {
        val toneData = ByteArray(22).also {
            it[0] = 127
            it[2] = 3
            it[3] = 20
            it[4] = 55
            it[6] = 2
            it[9] = 91
            it[10] = 18
            it[14] = 110
            it[16] = 3
            it[17] = 70
            it[18] = 40
        }
        val stream = bulkFrame(0x0E, 0x0F, 0x00, byteArrayOf()) +
            bulkFrame(0x30, 0x00, 0x00, toneData) +
            bulkFrame(0x0F, 0x0F, 0x00, byteArrayOf())

        val dump = RefaceProtocol.parsePatchDump(stream)

        assertEquals(61, dump.sysex.size)
        assertEquals("Sync", dump.parameters.oscillatorName)
        assertEquals("Delay", dump.parameters.effectName)
        assertEquals("Pitch", dump.parameters.lfoAssignmentName)
        assertEquals(91, dump.parameters.filterCutoff)
    }

    @Test
    fun `generates missing header and footer for lone tone block`() {
        val toneData = ByteArray(22).also {
            it[2] = 0
            it[6] = 4
            it[16] = 4
        }
        val dump = RefaceProtocol.parsePatchDump(bulkFrame(0x30, 0x00, 0x00, toneData))

        assertEquals(61, dump.sysex.size)
        assertEquals(3, RefaceProtocol.splitMessages(dump.sysex).size)
        assertEquals("FM", dump.parameters.oscillatorName)
    }

    @Test(expected = RefaceProtocol.InvalidDumpException::class)
    fun `rejects corrupted checksum`() {
        val toneData = ByteArray(22).also { it[2] = 1; it[6] = 1; it[16] = 1 }
        val frame = bulkFrame(0x30, 0x00, 0x00, toneData)
        frame[12] = 99
        RefaceProtocol.parsePatchDump(frame)
    }

    @Test
    fun `dump request has Yamaha CS address`() {
        assertArrayEquals(
            bytes(0xF0, 0x43, 0x20, 0x7F, 0x1C, 0x03, 0x0E, 0x0F, 0x00, 0xF7),
            RefaceProtocol.toneGeneratorDumpRequest
        )
        assertTrue(RefaceProtocol.toneGeneratorDumpRequest.all { (it.toInt() and 0xFF) <= 0xF7 })
    }

    private fun bulkFrame(high: Int, mid: Int, low: Int, data: ByteArray): ByteArray {
        val count = 4 + data.size
        val body = bytes(0x03, high, mid, low) + data
        val checksum = (-body.sumOf { it.toInt() and 0xFF }) and 0x7F
        return bytes(0xF0, 0x43, 0x00, 0x7F, 0x1C, count shr 7, count and 0x7F) +
            body + bytes(checksum, 0xF7)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

package com.refacecslive.protocol

/** Reassembles MIDI SysEx messages from arbitrarily fragmented MIDI byte streams. */
class SysExFramer(
    private val maxMessageBytes: Int = 4096,
    private val onMessage: (ByteArray) -> Unit
) {
    private val buffer = ArrayList<Byte>()
    private var inMessage = false

    fun accept(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        for (index in offset until offset + count) {
            val unsigned = bytes[index].toInt() and 0xFF

            // MIDI real-time bytes may legally occur inside a SysEx message.
            if (unsigned >= 0xF8) continue

            when (unsigned) {
                0xF0 -> {
                    buffer.clear()
                    buffer.add(bytes[index])
                    inMessage = true
                }

                0xF7 -> if (inMessage) {
                    buffer.add(bytes[index])
                    onMessage(buffer.toByteArray())
                    buffer.clear()
                    inMessage = false
                }

                else -> if (inMessage) {
                    buffer.add(bytes[index])
                    if (buffer.size > maxMessageBytes) {
                        buffer.clear()
                        inMessage = false
                    }
                }
            }
        }
    }

    fun reset() {
        buffer.clear()
        inMessage = false
    }
}

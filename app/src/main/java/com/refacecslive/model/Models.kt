package com.refacecslive.model

import com.refacecslive.protocol.RefaceProtocol
import java.util.UUID

data class Patch(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sysex: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    val parameters: RefaceProtocol.VoiceParameters
        get() = RefaceProtocol.parsePatchDump(sysex).parameters
}

data class PerformanceSet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val patchIds: List<String> = emptyList()
)

data class LibraryState(
    val patches: List<Patch>,
    val performanceSets: List<PerformanceSet>,
    val currentSetId: String?
)

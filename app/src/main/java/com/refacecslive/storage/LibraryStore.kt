package com.refacecslive.storage

import android.content.Context
import android.util.Base64
import com.refacecslive.model.LibraryState
import com.refacecslive.model.Patch
import com.refacecslive.model.PerformanceSet
import com.refacecslive.protocol.RefaceProtocol
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences("reface_cs_library", Context.MODE_PRIVATE)

    fun load(): LibraryState {
        val raw = preferences.getString(KEY_LIBRARY, null)
            ?: return LibraryState(emptyList(), listOf(PerformanceSet(name = "Live Set")), null)

        return try {
            decode(raw)
        } catch (_: Exception) {
            LibraryState(emptyList(), listOf(PerformanceSet(name = "Live Set")), null)
        }
    }

    fun save(state: LibraryState) {
        preferences.edit().putString(KEY_LIBRARY, encode(state)).apply()
    }

    fun encode(state: LibraryState): String = JSONObject().apply {
        put("format", "reface-cs-live-library")
        put("version", 1)
        put("currentSetId", state.currentSetId ?: JSONObject.NULL)
        put("patches", JSONArray().apply {
            state.patches.forEach { patch ->
                put(JSONObject().apply {
                    put("id", patch.id)
                    put("name", patch.name)
                    put("createdAt", patch.createdAt)
                    put("sysex", Base64.encodeToString(patch.sysex, Base64.NO_WRAP))
                })
            }
        })
        put("sets", JSONArray().apply {
            state.performanceSets.forEach { set ->
                put(JSONObject().apply {
                    put("id", set.id)
                    put("name", set.name)
                    put("patchIds", JSONArray(set.patchIds))
                })
            }
        })
    }.toString(2)

    fun decode(raw: String): LibraryState {
        val root = JSONObject(raw)
        require(root.optString("format") == "reface-cs-live-library") { "Unsupported backup format" }

        val patches = buildList {
            val array = root.optJSONArray("patches") ?: JSONArray()
            for (index in 0 until array.length()) {
                try {
                    val item = array.getJSONObject(index)
                    val sysex = Base64.decode(item.getString("sysex"), Base64.DEFAULT)
                    RefaceProtocol.parsePatchDump(sysex)
                    add(
                        Patch(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            sysex = sysex,
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                } catch (_: Exception) {
                    // A single broken patch must not make the whole library unavailable.
                }
            }
        }

        val patchIds = patches.mapTo(hashSetOf()) { it.id }
        val sets = buildList {
            val array = root.optJSONArray("sets") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val ids = buildList {
                    val idArray = item.optJSONArray("patchIds") ?: JSONArray()
                    for (idIndex in 0 until idArray.length()) {
                        idArray.optString(idIndex).takeIf(patchIds::contains)?.let(::add)
                    }
                }
                add(
                    PerformanceSet(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        patchIds = ids
                    )
                )
            }
        }.ifEmpty { listOf(PerformanceSet(name = "Live Set")) }

        val requestedCurrent = root.optString("currentSetId").takeIf { it.isNotBlank() }
        return LibraryState(
            patches = patches,
            performanceSets = sets,
            currentSetId = requestedCurrent?.takeIf { id -> sets.any { it.id == id } } ?: sets.first().id
        )
    }

    private companion object {
        const val KEY_LIBRARY = "library_v1"
    }
}

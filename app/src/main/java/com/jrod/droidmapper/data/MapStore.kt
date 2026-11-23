package com.jrod.droidmapper.data

import com.jrod.droidmapper.model.GameMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One line of the map list. */
data class MapInfo(val id: String, val name: String, val rooms: Int, val changed: Long)

/**
 * Every map is one small JSON file of facts in the app's private storage, under maps/.
 * The store also remembers which map was open, so the app comes back to it.
 */
class MapStore(root: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val dir = File(root, "maps")
    private val last = File(dir, "last-opened")

    init {
        dir.mkdirs()
        // Versions before 0.3 kept a single map in map.json: it becomes the first map in the list.
        val old = File(root, "map.json")
        if (old.exists()) {
            runCatching { json.decodeFromString<GameMap>(old.readText()) }.getOrNull()?.let { map ->
                val id = newId()
                save(id, map)
                setLastOpened(id)
            }
            old.delete()
        }
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun newId(): String {
        var n = System.currentTimeMillis()
        while (file("m" + n.toString(36)).exists()) n++
        return "m" + n.toString(36)
    }

    /** Most recently changed first. */
    fun list(): List<MapInfo> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        val id = f.name.removeSuffix(".json")
        load(id)?.let { MapInfo(id, it.name, it.rooms.size, f.lastModified()) }
    }.sortedByDescending { it.changed }

    fun load(id: String): GameMap? = runCatching { json.decodeFromString<GameMap>(file(id).readText()) }.getOrNull()

    fun save(id: String, map: GameMap) {
        dir.mkdirs()
        file(id).writeText(json.encodeToString(map))
    }

    /** A new map for a game, standing in its first room. Returns the new map's id. */
    fun create(name: String, firstRoom: String): String {
        val id = newId()
        save(id, GameMap.starting(name.trim().ifBlank { "Untitled map" }, firstRoom.trim()))
        return id
    }

    fun rename(id: String, name: String) {
        val map = load(id) ?: return
        if (name.isNotBlank()) save(id, map.copy(name = name.trim()))
    }

    fun delete(id: String) {
        file(id).delete()
        if (lastOpened() == null) last.delete()
    }

    /** The map that was open when the app was last used, if it still exists. */
    fun lastOpened(): String? = runCatching { last.readText().trim() }.getOrNull()?.takeIf { file(it).exists() }

    fun setLastOpened(id: String?) {
        if (id == null) last.delete() else last.writeText(id)
    }
}

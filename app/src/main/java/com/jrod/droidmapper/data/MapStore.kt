package com.jrod.droidmapper.data

import com.jrod.droidmapper.model.GameMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The map is one small JSON file of facts in the app's private storage. */
class MapStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file get() = File(dir, "map.json")

    fun load(): GameMap? = runCatching { json.decodeFromString<GameMap>(file.readText()) }.getOrNull()

    fun save(map: GameMap) {
        dir.mkdirs()
        file.writeText(json.encodeToString(map))
    }
}

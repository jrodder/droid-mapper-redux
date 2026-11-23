package com.jrod.droidmapper.ui

import androidx.lifecycle.ViewModel
import com.jrod.droidmapper.data.MapStore
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Placed
import com.jrod.droidmapper.model.cannotGo
import com.jrod.droidmapper.model.jumpTo
import com.jrod.droidmapper.model.layoutOf
import com.jrod.droidmapper.model.rename
import com.jrod.droidmapper.model.setOneWay
import com.jrod.droidmapper.model.walkTo
import com.jrod.droidmapper.model.walkToNew
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiState(
    val map: GameMap,
    val placed: Map<String, Placed>,
    /** The direction just tapped, while the app waits to hear where the player arrived. */
    val asking: Dir? = null,
    /** The direction just followed along a known passage, offered back as "Not here?". */
    val followed: Dir? = null,
    val canUndo: Boolean = false,
    /** The room whose card is open. */
    val editing: String? = null,
)

/**
 * One move is one tap on the direction pad. If the map already knows where that leads,
 * the player is moved there and nothing is asked. Otherwise the app asks where they
 * arrived, after the tap, when they actually know.
 */
class MapViewModel(private val store: MapStore) : ViewModel() {
    private val history = ArrayDeque<GameMap>()
    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    init {
        val map = store.load() ?: GameMap.starting("My map", "")
        // A brand-new map opens on the first room's card so it can be named.
        _state = MutableStateFlow(UiState(map, layoutOf(map), editing = map.current.takeIf { map.room(it)?.name.isNullOrBlank() }))
        state = _state.asStateFlow()
    }

    private fun commit(next: GameMap, followed: Dir? = null) {
        val s = _state.value
        if (next == s.map) {
            _state.value = s.copy(asking = null)
            return
        }
        history.addLast(s.map)
        if (history.size > 100) history.removeFirst()
        store.save(next)
        _state.value = s.copy(map = next, placed = layoutOf(next), asking = null, followed = followed, canUndo = true)
    }

    fun tapDirection(dir: Dir) {
        val s = _state.value
        val known = s.map.exit(s.map.current, dir)?.to
        if (known != null) commit(s.map.walkTo(dir, known), followed = dir)
        else _state.value = s.copy(asking = dir, followed = null)
    }

    fun arrivedAt(roomId: String) = _state.value.asking?.let { commit(_state.value.map.walkTo(it, roomId)) }
    fun arrivedSomewhereNew(name: String) = _state.value.asking?.let { commit(_state.value.map.walkToNew(it, name)) }
    fun couldNotGo() = _state.value.asking?.let { commit(_state.value.map.cannotGo(it)) }
    fun stopAsking() { _state.value = _state.value.copy(asking = null) }

    /** The passage just followed did not lead where the map said: take the move back and ask. */
    fun notHere() {
        val dir = _state.value.followed ?: return
        undo()
        _state.value = _state.value.copy(asking = dir)
    }

    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        store.save(prev)
        _state.value = UiState(prev, layoutOf(prev), canUndo = history.isNotEmpty())
    }

    fun openRoom(roomId: String) { _state.value = _state.value.copy(editing = roomId) }
    fun closeRoom() { _state.value = _state.value.copy(editing = null) }
    fun rename(roomId: String, name: String) = commit(_state.value.map.rename(roomId, name))
    fun imHere(roomId: String) { commit(_state.value.map.jumpTo(roomId)); closeRoom() }
    fun setOneWay(from: String, dir: Dir, oneWay: Boolean) = commit(_state.value.map.setOneWay(from, dir, oneWay))
}

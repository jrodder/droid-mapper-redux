package com.jrod.droidmapper.ui

import androidx.lifecycle.ViewModel
import com.jrod.droidmapper.data.MapStore
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Folded
import com.jrod.droidmapper.model.Spot
import com.jrod.droidmapper.model.foldTangles
import com.jrod.droidmapper.model.toggleUnexplored
import com.jrod.droidmapper.model.cannotGo
import com.jrod.droidmapper.model.deleteRoom
import com.jrod.droidmapper.model.editRoom
import com.jrod.droidmapper.model.jumpTo
import com.jrod.droidmapper.model.mergeRooms
import com.jrod.droidmapper.model.redirect
import com.jrod.droidmapper.model.removePassage
import com.jrod.droidmapper.model.floorsOf
import com.jrod.droidmapper.model.setOneWay
import com.jrod.droidmapper.model.walkTo
import com.jrod.droidmapper.model.walkToNew
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiState(
    val map: GameMap,
    /** The map as drawn: tangles folded into one box each. */
    val folded: Folded,
    /** Where each drawn room (or tangle box) sits on the floors map. */
    val placed: Map<String, Spot>,
    /** The direction just tapped, while the app waits to hear where the player arrived. */
    val asking: Dir? = null,
    /** The direction just followed along a known passage, offered back as "Not here?". */
    val followed: Dir? = null,
    val canUndo: Boolean = false,
    /** The room whose card is open. */
    val editing: String? = null,
    /** The room being looked at (read-only), after a single tap. */
    val viewing: String? = null,
)

/**
 * One move is one tap on the direction pad. If the map already knows where that leads,
 * the player is moved there and nothing is asked. Otherwise the app asks where they
 * arrived, after the tap, when they actually know.
 */
class MapViewModel(private val store: MapStore, private val mapId: String) : ViewModel() {
    private val history = ArrayDeque<GameMap>()
    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    init {
        val map = store.load(mapId) ?: GameMap.starting("Untitled map", "")
        // A brand-new map opens on the first room's card so it can be named.
        val folded = foldTangles(map)
        _state = MutableStateFlow(
            UiState(map, folded, floorsOf(folded.map), editing = map.current.takeIf { map.room(it)?.name.isNullOrBlank() }),
        )
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
        store.save(mapId, next)
        val folded = foldTangles(next)
        _state.value = s.copy(
            map = next, folded = folded, placed = floorsOf(folded.map), asking = null, followed = followed, canUndo = true,
        )
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
        store.save(mapId, prev)
        val folded = foldTangles(prev)
        _state.value = UiState(prev, folded, floorsOf(folded.map), canUndo = history.isNotEmpty())
    }

    fun openRoom(roomId: String) { _state.value = _state.value.copy(editing = roomId, viewing = null) }
    fun viewRoom(roomId: String) { _state.value = _state.value.copy(viewing = roomId) }
    fun closeView() { _state.value = _state.value.copy(viewing = null) }
    fun closeRoom() { _state.value = _state.value.copy(editing = null) }
    fun saveRoom(roomId: String, name: String, description: String, items: String) =
        commit(_state.value.map.editRoom(roomId, name, description, items))
    fun imHere(roomId: String) { commit(_state.value.map.jumpTo(roomId)); closeRoom() }
    fun setOneWay(from: String, dir: Dir, oneWay: Boolean) = commit(_state.value.map.setOneWay(from, dir, oneWay))

    /** Note (or stop noting) an exit the game mentions that has not been tried. */
    fun toggleUnexplored(roomId: String, dir: Dir) = commit(_state.value.map.toggleUnexplored(roomId, dir))

    // Fixing things that only become clear later. Each is one undo step.
    fun stillHere() = _state.value.asking?.let { commit(_state.value.map.walkTo(it, _state.value.map.current)) }
    fun removeExit(from: String, dir: Dir) = commit(_state.value.map.removePassage(from, dir))
    fun redirectExit(from: String, dir: Dir, to: String) = commit(_state.value.map.redirect(from, dir, to))

    /** The room whose card is open is really [keep]: fold it in and show the survivor's card. */
    fun sameRoomAs(keep: String) {
        val drop = _state.value.editing ?: return
        commit(_state.value.map.mergeRooms(keep, drop))
        _state.value = _state.value.copy(editing = keep)
    }

    fun deleteRoom(roomId: String) {
        commit(_state.value.map.deleteRoom(roomId))
        closeRoom()
    }
}

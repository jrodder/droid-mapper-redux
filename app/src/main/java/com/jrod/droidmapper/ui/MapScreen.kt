package com.jrod.droidmapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Passage
import com.jrod.droidmapper.model.Room
import com.jrod.droidmapper.model.displayNames
import com.jrod.droidmapper.model.guessArrival

@Composable
fun MapScreen(vm: MapViewModel, onMaps: () -> Unit) {
    val s by vm.state.collectAsState()
    BackHandler(onBack = onMaps)        // back leaves the map for the list (unless a question is open, below)
    val names = remember(s.map.rooms) { s.map.displayNames() }
    // The whole screen rises above the keyboard, so nothing the player needs is ever behind it.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onMaps) { Text("\u2039 Maps") }
            Text(
                names[s.map.current] ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::undo, enabled = s.canUndo) { Text("Undo") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // While the app is asking where you arrived, tapping a room on the map is an answer.
            // A double tap or a long press opens a room's card: everything about it, and every fix.
            MapCanvas(
                s.map, s.placed, names,
                // A single tap shows what is known about a room; while the app is asking, it answers.
                onTapRoom = { if (s.asking != null) vm.arrivedAt(it) else vm.viewRoom(it) },
                onOpenRoom = { if (s.asking == null) vm.openRoom(it) },
            )
        }
        val asking = s.asking
        if (asking != null) {
            BackHandler(onBack = vm::stopAsking)
            ArrivalPanel(
                dir = asking,
                map = s.map,
                names = names,
                guesses = remember(s.map, asking) { guessArrival(s.map, s.placed, asking) }.map { it.id },
                onRoom = { vm.arrivedAt(it) },
                onNew = { vm.arrivedSomewhereNew(it) },
                onCouldNot = { vm.couldNotGo() },
                onStillHere = { vm.stillHere() },
                onCancel = vm::stopAsking,
            )
        } else {
            s.followed?.let { dir ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Went ${dir.word}: now in ${names[s.map.current]}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::notHere) { Text("Not here?") }
                }
            }
            DirectionPad(s.map, onDirection = vm::tapDirection, onHere = { vm.openRoom(s.map.current) })
        }
    }
    s.viewing?.let { id ->
        s.map.room(id)?.let { room -> RoomGlance(room, s.map, names, onClose = vm::closeView) }
    }
    s.editing?.let { id ->
        s.map.room(id)?.let { room ->
            RoomCard(
                room = room,
                map = s.map,
                names = names,
                onSave = { n, d, i -> vm.saveRoom(id, n, d, i) },
                onImHere = { vm.imHere(id) },
                onOneWay = { d, on -> vm.setOneWay(id, d, on) },
                onRedirect = { d, to -> vm.redirectExit(id, d, to) },
                onRemoveExit = { d -> vm.removeExit(id, d) },
                onSameAs = { vm.sameRoomAs(it) },
                onDelete = { vm.deleteRoom(id) },
                onClose = vm::closeRoom,
            )
        }
    }
}

/**
 * Always on screen: the eight compass points around the room you are in, with up, down,
 * in and out beside them. One tap is one move. A filled button already leads somewhere;
 * an outlined one is a way not yet tried; a dimmed one is a way you could not go.
 */
@Composable
fun DirectionPad(map: GameMap, onDirection: (Dir) -> Unit, onHere: () -> Unit) {
    val here = map.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in listOf(listOf(Dir.NW, Dir.N, Dir.NE), listOf(Dir.W, null, Dir.E), listOf(Dir.SW, Dir.S, Dir.SE))) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in row) {
                        if (d == null) PadKey("•", "This room", filled = false, dim = false, onClick = onHere)
                        else PadKey(d, map.exit(here, d), onDirection)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in listOf(listOf(Dir.UP, Dir.IN), listOf(Dir.DOWN, Dir.OUT))) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in row) PadKey(d, map.exit(here, d), onDirection)
                }
            }
        }
    }
}

@Composable
private fun PadKey(dir: Dir, exit: Passage?, onDirection: (Dir) -> Unit) {
    val state = when {
        exit == null -> "not tried"
        exit.to == null -> "could not go"
        else -> "known"
    }
    PadKey(
        text = dir.label, description = "${dir.word}, $state",
        filled = exit?.to != null, dim = exit != null && exit.to == null,
    ) { onDirection(dir) }
}

@Composable
private fun PadKey(text: String, description: String, filled: Boolean, dim: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (filled) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(1.dp, if (filled) scheme.primary else scheme.outline),
        modifier = Modifier.size(54.dp).alpha(if (dim) 0.4f else 1f)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

/**
 * Asked after the direction tap, when the player knows the answer: where did you arrive?
 * It takes the direction pad's place at the bottom of the screen, so the map stays in view.
 * The app's best guesses come first, so closing a loop is one more tap; a room can also be
 * picked by tapping it on the map.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrivalPanel(
    dir: Dir,
    map: GameMap,
    names: Map<String, String>,
    guesses: List<String>,
    onRoom: (String) -> Unit,
    onNew: (String) -> Unit,
    onCouldNot: () -> Unit,
    onStillHere: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(dir, map.current) { mutableStateOf("") }
    // Answering puts the keyboard away, so the map and the direction pad are never left covered.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun answered(action: () -> Unit) { focus.clearFocus(); keyboard?.hide(); action() }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Went ${dir.word} from ${names[map.current]}. Where are you now?",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { answered(onCancel) }) { Text("Cancel") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in guesses) {
                Button(onClick = { answered { onRoom(id) } }) {
                    Text(names[id] ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(onClick = { answered(onCouldNot) }) { Text("Couldn't go") }
            // the game moved you but you are in the same room: a passage that loops back
            OutlinedButton(onClick = { answered(onStillHere) }) { Text("Still here") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Somewhere new: its name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) answered { onNew(name) } }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { answered { onNew(name) } }, enabled = name.isNotBlank()) { Text("New") }
        }
        // Typing narrows the known rooms, so a room that was not guessed is still two taps away.
        val typed = name.trim()
        if (typed.length >= 2) {
            val found = map.rooms.filter { it.id != map.current && it.id !in guesses }
                .filter { (names[it.id] ?: "").contains(typed, ignoreCase = true) }.take(3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in found) {
                    TextButton(onClick = { answered { onRoom(r.id) } }) { Text("Back in ${names[r.id]}") }
                }
            }
        }
    }
}

/**
 * What is known about a room, in a frame over the map. Nothing here can be changed; a tap
 * anywhere puts it away. (Editing is a double tap or a long press on the room.)
 */
@Composable
private fun RoomGlance(room: Room, map: GameMap, names: Map<String, String>, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose) {
        Surface(
            color = scheme.surface,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(3.dp, scheme.secondary),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClose),
        ) {
            // a second, thin rule inside the first, like the frame of a printed plate
            Surface(
                color = scheme.surface,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, scheme.outline),
                modifier = Modifier.padding(6.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
                    Text(names[room.id] ?: "", style = MaterialTheme.typography.titleLarge)
                    if (room.id == map.current) {
                        Text("You are here", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    }
                    Text(
                        room.description.ifBlank { "No description yet." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (room.description.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    if (room.items.isNotBlank()) {
                        Text("Items", style = MaterialTheme.typography.titleSmall, color = scheme.secondary, modifier = Modifier.padding(top = 12.dp))
                        Text(room.items, style = MaterialTheme.typography.bodyLarge)
                    }
                    val exits = map.exitsOf(room.id).sortedBy { it.dir.ordinal }
                    if (exits.isNotEmpty()) {
                        Text("Exits", style = MaterialTheme.typography.titleSmall, color = scheme.secondary, modifier = Modifier.padding(top = 12.dp))
                        for (e in exits) {
                            val to = e.to
                            val where = when {
                                to == null -> "couldn't go"
                                to == room.id -> "back here"
                                else -> (names[to] ?: "") + if (e.oneWay) " (one-way)" else ""
                            }
                            Text("${e.dir.word}: $where", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        "Tap to close. Double-tap or hold a room to edit it.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * A room's card: what it is called, what the game says about it, what is lying there, and
 * every way to put right something that only became clear later.
 */
@Composable
private fun RoomCard(
    room: Room,
    map: GameMap,
    names: Map<String, String>,
    onSave: (name: String, description: String, items: String) -> Unit,
    onImHere: () -> Unit,
    onOneWay: (Dir, Boolean) -> Unit,
    onRedirect: (Dir, String) -> Unit,
    onRemoveExit: (Dir) -> Unit,
    onSameAs: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    var description by remember(room.id) { mutableStateOf(room.description) }
    var items by remember(room.id) { mutableStateOf(room.items) }
    var picking by remember(room.id) { mutableStateOf<Pick?>(null) }
    var confirmDelete by remember(room.id) { mutableStateOf(false) }
    // Leaving the card any way at all saves it; a blank name keeps the old one.
    fun save() = onSave(name.ifBlank { room.name }, description, items)
    val exits = map.exitsOf(room.id)
    AlertDialog(
        onDismissRequest = { save(); onClose() },
        title = { Text(if (room.name.isBlank()) "Name the room you start in" else "Room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, label = { Text("Description") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = items, onValueChange = { items = it }, label = { Text("Items here") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2, maxLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (exits.isNotEmpty()) {
                    Text("Exits", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
                }
                for (e in exits) {
                    val to = e.to
                    val where = when {
                        to == null -> "couldn't go"
                        to == room.id -> "back here"
                        e.assumed -> "${names[to]} (assumed)"
                        else -> names[to] ?: ""
                    }
                    Text("${e.dir.word}: $where", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (to != null && to != room.id && !e.assumed) {
                            Text("One-way", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = e.oneWay, onCheckedChange = { onOneWay(e.dir, it) }, modifier = Modifier.padding(horizontal = 6.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { picking = Pick.Redirect(e.dir) }) { Text("Leads elsewhere") }
                        TextButton(onClick = { onRemoveExit(e.dir) }) { Text("Remove") }
                    }
                }
                Text("This room", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
                Row {
                    if (map.rooms.size > 1) {
                        TextButton(onClick = { save(); picking = Pick.SameAs }) { Text("Same room as\u2026") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onClose() }) { Text("Done") } },
        dismissButton = { if (room.id != map.current) TextButton(onClick = { save(); onImHere() }) { Text("I'm here now") } },
    )
    picking?.let { pick ->
        RoomPicker(
            title = when (pick) {
                is Pick.Redirect -> "Going ${pick.dir.word} from ${names[room.id]} leads to\u2026"
                Pick.SameAs -> "${names[room.id]} is the same room as\u2026"
            },
            rooms = map.rooms.filter { it.id != room.id || pick is Pick.Redirect },
            names = names,
            onPick = { chosen ->
                picking = null
                when (pick) {
                    is Pick.Redirect -> onRedirect(pick.dir, chosen)
                    Pick.SameAs -> onSameAs(chosen)
                }
            },
            onCancel = { picking = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${names[room.id]}?") },
            text = { Text("The room and every passage to or from it are removed. Undo brings them back.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private sealed interface Pick {
    data class Redirect(val dir: Dir) : Pick
    data object SameAs : Pick
}

/** Choose one of the known rooms, narrowing the list by typing. */
@Composable
private fun RoomPicker(title: String, rooms: List<Room>, names: Map<String, String>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it }, label = { Text("Find a room") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val shown = rooms.filter { (names[it.id] ?: "").contains(filter.trim(), ignoreCase = true) }
                        .sortedBy { (names[it.id] ?: "").lowercase() }
                    for (r in shown) {
                        TextButton(onClick = { onPick(r.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(names[r.id] ?: "", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

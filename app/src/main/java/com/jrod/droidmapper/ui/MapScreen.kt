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
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Passage
import com.jrod.droidmapper.model.Room
import com.jrod.droidmapper.model.displayNames
import com.jrod.droidmapper.model.guessArrival

@Composable
fun MapScreen(vm: MapViewModel) {
    val s by vm.state.collectAsState()
    val names = remember(s.map.rooms) { s.map.displayNames() }
    // The whole screen rises above the keyboard, so nothing the player needs is ever behind it.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // A double tap opens a room's card (name, description, items).
            MapCanvas(
                s.map, s.placed, names,
                onTapRoom = { if (s.asking != null) vm.arrivedAt(it) },
                onDoubleTapRoom = { if (s.asking == null) vm.openRoom(it) },
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
    s.editing?.let { id ->
        s.map.room(id)?.let { room ->
            RoomCard(
                room = room,
                isCurrent = id == s.map.current,
                exits = s.map.exitsOf(id).filter { it.to != null && it.to != id && !it.assumed },
                names = names,
                onSave = { n, d, i -> vm.saveRoom(id, n, d, i) },
                onImHere = { vm.imHere(id) },
                onOneWay = { d, on -> vm.setOneWay(id, d, on) },
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

/** A room's card: what it is called, what the game says about it, and what is lying there. */
@Composable
private fun RoomCard(
    room: Room,
    isCurrent: Boolean,
    exits: List<Passage>,
    names: Map<String, String>,
    onSave: (name: String, description: String, items: String) -> Unit,
    onImHere: () -> Unit,
    onOneWay: (Dir, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    var description by remember(room.id) { mutableStateOf(room.description) }
    var items by remember(room.id) { mutableStateOf(room.items) }
    // Leaving the card any way at all saves it; a blank name keeps the old one.
    fun save() = onSave(name.ifBlank { room.name }, description, items)
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
                    Text("One-way?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                }
                for (e in exits) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.dir.label} to ${names[e.to] ?: ""}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Switch(checked = e.oneWay, onCheckedChange = { onOneWay(e.dir, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onClose() }) { Text("Done") } },
        dismissButton = { if (!isCurrent) TextButton(onClick = { save(); onImHere() }) { Text("I'm here now") } },
    )
}

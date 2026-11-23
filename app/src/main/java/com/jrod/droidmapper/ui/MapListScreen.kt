package com.jrod.droidmapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jrod.droidmapper.data.MapInfo
import com.jrod.droidmapper.data.MapStore
import java.text.DateFormat
import java.util.Date

/** The list of maps: one per game. Tap one to open it; make, rename and delete them here. */
@Composable
fun MapListScreen(store: MapStore, onOpen: (String) -> Unit) {
    var maps by remember { mutableStateOf(store.list()) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MapInfo?>(null) }
    var deleting by remember { mutableStateOf<MapInfo?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Maps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }) { Text("New map") }
        }
        if (maps.isEmpty()) {
            Text(
                "No maps yet. Start one for the game you're playing.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(maps, key = { it.id }) { info ->
                Surface(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { onOpen(info.id) },
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(info.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${info.rooms} ${if (info.rooms == 1) "room" else "rooms"} · " +
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info.changed)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { renaming = info }) { Text("Rename") }
                        TextButton(onClick = { deleting = info }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var room by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New map") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Game") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = room, onValueChange = { room = it }, label = { Text("The room you start in") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = room.isNotBlank(), onClick = { creating = false; onOpen(store.create(name, room)) }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
    renaming?.let { info ->
        var name by remember(info.id) { mutableStateOf(info.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename map") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Game") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { store.rename(info.id, name); renaming = null; maps = store.list() }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { info ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${info.name}\"?") },
            text = { Text("This removes the map and all ${info.rooms} of its rooms. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { store.delete(info.id); deleting = null; maps = store.list() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

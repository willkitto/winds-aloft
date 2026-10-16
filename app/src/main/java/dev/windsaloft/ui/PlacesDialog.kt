package dev.windsaloft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.windsaloft.UiState
import dev.windsaloft.data.SavedPlace

/** Saved places (favourites) and the history of recent spots; tap one to go there. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlacesDialog(
    state: UiState,
    onGo: (SavedPlace) -> Unit,
    onToggleFavorite: (SavedPlace) -> Unit,
    onRename: (SavedPlace, String) -> Unit,
    onRemoveRecent: (SavedPlace) -> Unit,
    onClearRecents: () -> Unit,
    onSaveAllOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Places") },
        text = { PlacesContent(state, onGo, onToggleFavorite, onRename, onRemoveRecent, onClearRecents, onSaveAllOffline) },
    )
}

/** The body of [PlacesDialog]: tabs, the list, and the offline download button. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlacesContent(
    state: UiState,
    onGo: (SavedPlace) -> Unit,
    onToggleFavorite: (SavedPlace) -> Unit,
    onRename: (SavedPlace, String) -> Unit,
    onRemoveRecent: (SavedPlace) -> Unit,
    onClearRecents: () -> Unit,
    onSaveAllOffline: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(if (state.favorites.isEmpty() && state.recents.isNotEmpty()) 1 else 0) }
    var renaming by remember { mutableStateOf<SavedPlace?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Saved (${state.favorites.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Recent (${state.recents.size})") })
        }
        if (!state.online) {
            Text(
                "Offline: places show the winds saved on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val list = if (tab == 0) state.favorites else state.recents
        if (list.isEmpty()) {
            Text(
                if (tab == 0) "No saved places yet. Tap ☆ on a spot's panel to save it. Saved places have " +
                    "their winds downloaded whenever you're online, ready for when you're not."
                else "Spots you look at appear here (the last ${SavedPlace.MAX_RECENTS}).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                list.forEach { place ->
                    if (renaming == place) RenameRow(place, onDone = { name ->
                        if (name != null) onRename(place, name)
                        renaming = null
                    }) else PlaceRow(
                        state, place, tab == 0,
                        onGo = { onGo(place) },
                        onToggleFavorite = { onToggleFavorite(place) },
                        onRename = { renaming = place },
                        onRemove = { if (tab == 0) onToggleFavorite(place) else onRemoveRecent(place) },
                    )
                }
            }
        }
        if (tab == 0 && state.favorites.isNotEmpty()) {
            val progress = state.savingPlaces
            if (progress != null) {
                Text("Downloading winds… ${progress.first + 1} of ${progress.second}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { (progress.first + 1).toFloat() / progress.second },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(onClick = onSaveAllOffline, enabled = state.online, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.Download, contentDescription = null)
                    Text("  Download winds for all saved places")
                }
            }
        }
        if (tab == 1 && state.recents.isNotEmpty()) {
            TextButton(onClick = onClearRecents, modifier = Modifier.align(Alignment.End)) { Text("Clear history") }
        }
    }
}

@Composable
private fun PlaceRow(
    state: UiState, place: SavedPlace, isSavedTab: Boolean,
    onGo: () -> Unit, onToggleFavorite: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit,
) {
    val saved = state.savedWindsAt(place.location)
    val favorite = isSavedTab || state.favorites.any { it.sameSpot(place.location) }
    ListItem(
        headlineContent = {
            Text(place.name ?: Format.coords(place.location), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val parts = listOfNotNull(
                if (saved != null) "Winds saved ${Format.age(saved)}" else "No winds saved",
                if (place.name != null) Format.coords(place.location) else null,
            )
            Text(
                parts.joinToString(" · "),
                color = if (saved == null && !state.online) MaterialTheme.colorScheme.error else Color.Unspecified,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row {
                if (isSavedTab) {
                    IconButton(onClick = onRename) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove from saved places") }
                } else {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (favorite) Icons.Default.Star else AppIcons.StarOutline,
                            contentDescription = if (favorite) "Remove from saved places" else "Save this place",
                            tint = if (favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove from history") }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onGo),
    )
}

@Composable
private fun RenameRow(place: SavedPlace, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(place.name.orEmpty()) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("Name") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onDone(null) }) { Text("Cancel") }
        TextButton(onClick = { onDone(text) }) { Text("Save") }
    }
}

package com.elmagnifico.retroiso

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = GameStore(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(store) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: GameStore) {
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(store.load()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            progress = 0f
            try {
                store.importIso(uri) { progress = it }
                games = store.load()
            } catch (e: Exception) {
                message = "Erreur : ${e.message}"
            } finally {
                progress = null
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mes jeux") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Ajouter un ISO") }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (games.isEmpty()) {
                Text(
                    "Aucun jeu pour l'instant.\nAppuie sur « Ajouter un ISO ».",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(games, key = { it.id }) { g ->
                        GameCard(
                            g,
                            onPlay = { message = "Le moteur de compatibilité n'est pas encore branché (étape suivante).\nLes fichiers de « ${g.name} » sont bien extraits." },
                            onDelete = { store.delete(g); games = store.load() }
                        )
                    }
                }
            }
        }
    }

    progress?.let {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Extraction de l'ISO…") },
            text = { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            text = { Text(it) }
        )
    }
}

@Composable
fun GameCard(game: Game, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(game.name, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2)
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("Jouer") }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Supprimer") }
        }
    }
}

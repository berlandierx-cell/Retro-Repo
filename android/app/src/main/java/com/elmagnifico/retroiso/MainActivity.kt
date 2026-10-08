package com.elmagnifico.retroiso

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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

fun launchWinlator(ctx: Context): String? {
    val i = ctx.packageManager.getLaunchIntentForPackage("com.winlator") ?: return "Winlator n'est pas installé."
    ctx.startActivity(i)
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: GameStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(store.load()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<Game?>(null) }
    var patchFor by remember { mutableStateOf<Game?>(null) }

    val isoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val g = patchFor
        if (uri != null && g != null) scope.launch {
            try {
                store.copyPatch(g, uri)
                message = "Patch copié dans RetroIso/${g.dir}/Patch."
            } catch (e: Exception) {
                message = "Erreur : ${e.message}"
            }
        }
    }

    fun needFiles(): Boolean {
        if (Environment.isExternalStorageManager()) return false
        message = "Autorise « Accès à tous les fichiers » pour Retro ISO, puis reviens ici et recommence."
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        return true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mes jeux") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!needFiles()) isoPicker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Ajouter un ISO") }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (games.isEmpty()) {
                Text(
                    "Aucun jeu pour l'instant.\nAppuie sur « Ajouter un ISO » et choisis-le dans Google Drive.",
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
                            onPlay = { playing = g },
                            onPatch = { if (!needFiles()) { patchFor = g; patchPicker.launch(arrayOf("*/*")) } },
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
    playing?.let { g ->
        AlertDialog(
            onDismissRequest = { playing = null },
            title = { Text(g.name) },
            text = { Text("Dans Winlator, ouvre le lecteur D:, puis le dossier RetroIso/${g.dir}, et lance setup.exe (le patch est dans le sous-dossier Patch).") },
            confirmButton = { TextButton(onClick = { message = launchWinlator(ctx); playing = null }) { Text("Ouvrir Winlator") } },
            dismissButton = { TextButton(onClick = { playing = null }) { Text("Fermer") } }
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
fun GameCard(game: Game, onPlay: () -> Unit, onPatch: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(game.name, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2)
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("Jouer") }
            OutlinedButton(onClick = onPatch, modifier = Modifier.fillMaxWidth()) { Text("Ajouter un patch") }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Supprimer") }
        }
    }
}

package com.elmagnifico.retroiso

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
import com.elmagnifico.retroiso.runtime.RuntimeManager
import com.elmagnifico.retroiso.runtime.winlator.EmbeddedWinlatorBackend
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
    val ctx = LocalContext.current
    val runtime = remember { RuntimeManager(ctx) }
    val embedded = remember { EmbeddedWinlatorBackend(ctx.applicationContext) }
    val scope = rememberCoroutineScope()

    var games by remember { mutableStateOf(store.load()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var runtimeBusy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var patchFor by remember { mutableStateOf<Game?>(null) }

    val isoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers do not expose persistable permissions.
            }
            progress = 0f
            try {
                store.importIso(uri) { progress = it }
                games = store.load()
            } catch (e: Exception) {
                message = "Erreur : " + e.message
            } finally {
                progress = null
            }
        }
    }

    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val g = patchFor
        if (uris.isNotEmpty() && g != null) scope.launch {
            try {
                uris.forEach { store.copyPatch(g, it) }
                message = uris.size.toString() + " correctif(s) ajouté(s). Les packs .7z sont extraits automatiquement."
            } catch (e: Exception) {
                message = "Erreur : " + e.message
            }
        }
    }

    fun needFiles(): Boolean {
        if (Environment.isExternalStorageManager()) return false
        message = "Autorise « Accès à tous les fichiers » pour Retro ISO, puis reviens ici."
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            )
        )
        return true
    }

    fun play(game: Game) {
        scope.launch {
            runtimeBusy = game.name
            try {
                if (!store.hasLocalCache(game)) {
                    progress = 0f
                    store.ensureCached(game) { progress = it }
                    progress = null
                }
                val plan = runtime.prepare(game, store.gameDir(game))
                val prepared = embedded.prepare(game, store.gameDir(game), plan.profile)
                if (prepared.gameExecutable != null && prepared.patchExecutable != null) {
                    if (!embedded.isPatchApplied(prepared)) {
                        embedded.launchPatch(prepared)
                        message = "Patch 1.0.7 lancé. Termine l'updater puis appuie de nouveau sur Jouer."
                    } else {
                        embedded.launchGame(prepared)
                    }
                } else if (prepared.gameExecutable != null) {
                    embedded.launchGame(prepared)
                } else if (prepared.patchExecutable != null || prepared.compatibilityDlls.isNotEmpty()) {
                    throw IllegalStateException(
                        "Le jeu semble déjà installé mais Gangsters2.exe est introuvable. " +
                        "Retro ISO refuse de relancer Setup.exe pour éviter une réinstallation en boucle."
                    )
                } else {
                    embedded.launchInstaller(prepared)
                }
            } catch (e: Exception) {
                message = e.message ?: "Impossible de démarrer le moteur Retro ISO."
            } finally {
                progress = null
                runtimeBusy = null
            }
        }
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
                    columns = GridCells.Adaptive(170.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(games, key = { it.id }) { g ->
                        GameCard(
                            game = g,
                            onPlay = { play(g) },
                            onPatch = {
                                if (!needFiles()) {
                                    patchFor = g
                                    patchPicker.launch(arrayOf("*/*"))
                                }
                            },
                            onReleaseCache = {
                                store.releaseCache(g)
                                games = store.load()
                                message = "Cache local libéré. L'ISO reste disponible depuis Drive."
                            },
                            onDelete = {
                                store.delete(g)
                                games = store.load()
                            }
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
            title = { Text("Import du jeu…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Conservation de l'ISO et analyse du CD.")
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
            }
        )
    }

    runtimeBusy?.let { gameName ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Préparation de $gameName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Initialisation de Wine/Box64, création du container et préparation du CD-ROM…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("La première exécution peut être plus longue car le moteur est installé dans Retro ISO.")
                }
            }
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
fun GameCard(
    game: Game,
    onPlay: () -> Unit,
    onPatch: () -> Unit,
    onReleaseCache: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(game.name, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2)
            if (!game.iso.isNullOrBlank()) {
                val source = if (!game.sourceUri.isNullOrBlank()) "Drive" else "local"
                Text("Bibliothèque : $source • moteur intégré", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("Jouer") }
            OutlinedButton(onClick = onPatch, modifier = Modifier.fillMaxWidth()) { Text("Ajouter un patch") }
            if (!game.sourceUri.isNullOrBlank()) {
                TextButton(onClick = onReleaseCache, modifier = Modifier.fillMaxWidth()) {
                    Text("Libérer le cache local")
                }
            }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Supprimer") }
        }
    }
}

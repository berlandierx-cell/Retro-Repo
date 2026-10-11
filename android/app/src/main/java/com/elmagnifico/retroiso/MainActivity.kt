package com.elmagnifico.retroiso

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.elmagnifico.retroiso.runtime.winlator.RetroIsoDiagnostics

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
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var diagnosticCopied by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var patchFor by remember { mutableStateOf<Game?>(null) }
    val programPreferences = remember { ctx.getSharedPreferences("program-selection", 0) }
    var programGame by remember { mutableStateOf<Game?>(null) }
    var programContainer by remember { mutableStateOf<EmbeddedWinlatorBackend.PreparedContainer?>(null) }
    var programs by remember { mutableStateOf<List<EmbeddedWinlatorBackend.Program>>(emptyList()) }
    var selectedProgram by remember { mutableStateOf<String?>(null) }

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
                    if (embedded.isPatchApplied(prepared)) {
                        embedded.launchGame(prepared)
                    } else if (embedded.canApplyGangstersPatch(prepared)) {
                        embedded.launchPatch(prepared)
                        message = "Patch 1.0.7 lancé. Termine l'updater puis appuie de nouveau sur Jouer."
                    } else {
                        // Some installations use only the spawned executable.
                        // Do not block a working installation on an incompatible updater.
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

    fun chooseProgram(game: Game) {
        if (runtimeBusy != null || progress != null) return
        programGame = null
        runtimeBusy = game.name
        scope.launch {
            try {
                if (!store.hasLocalCache(game)) {
                    progress = 0f
                    store.ensureCached(game) { progress = it }
                    progress = null
                }
                val plan = runtime.prepare(game, store.gameDir(game))
                val prepared = embedded.prepare(game, store.gameDir(game), plan.profile, requireInstaller = false)
                val available = embedded.listPrograms(prepared)
                val remembered = selectedProgram.takeIf { programContainer?.containerId == prepared.containerId }
                    ?: programPreferences.getString(game.id.toString(), null)
                selectedProgram = available.firstOrNull { it.dosPath == remembered }?.dosPath
                    ?: available.firstOrNull { it.file == prepared.installerFile }?.dosPath
                    ?: available.firstOrNull()?.dosPath
                programs = available
                programContainer = prepared
                programGame = game
            } catch (e: Exception) {
                message = e.message ?: "Impossible de lire les programmes."
            } finally {
                progress = null
                runtimeBusy = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes jeux") },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            diagnostic = withContext(Dispatchers.IO) { RetroIsoDiagnostics.readLatest(ctx) }
                            diagnosticCopied = false
                        }
                    }) { Text("Diagnostic") }
                }
            )
        },
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
                            onChooseProgram = { if (!needFiles()) chooseProgram(g) },
                            onPatch = {
                                if (!needFiles()) {
                                    patchFor = g
                                    patchPicker.launch(arrayOf("*/*"))
                                }
                            },
                            onInspectFiles = {
                                scope.launch {
                                    try {
                                        val plan = runtime.prepare(g, store.gameDir(g))
                                        val prepared = embedded.prepare(g, store.gameDir(g), plan.profile)
                                        message = embedded.describeInstalledExecutables(prepared)
                                    } catch (e: Exception) {
                                        message = "Erreur inventaire : " + e.message
                                    }
                                }
                            },
                            onReleaseCache = {
                                store.releaseCache(g)
                                games = store.load()
                                message = "Cache local libéré. L'ISO reste disponible depuis Drive."
                            },
                            onDelete = {
                                scope.launch {
                                    try {
                                        runtimeBusy = "Suppression de " + g.name
                                        runtime.loadProfileForName(g.name)?.let { profile ->
                                            embedded.deleteInstalledGame(profile)
                                        }
                                        store.delete(g)
                                        games = store.load()
                                        message = "Jeu supprimé complètement : installation Wine, registre, ISO, CD et patch effacés."
                                    } catch (e: Exception) {
                                        message = "Erreur pendant la suppression complète : " + (e.message ?: "inconnue")
                                    } finally {
                                        runtimeBusy = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    programGame?.let { game ->
        AlertDialog(
            onDismissRequest = { programGame = null },
            title = { Text("Choisir un programme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("X: CD • C: programmes installés • D: patchs")
                    TextButton(onClick = { chooseProgram(game) }) { Text("Actualiser les programmes") }
                    if (programs.isEmpty()) {
                        Text("Aucun fichier .exe trouvé. Ajoute le CD ou un patch, puis actualise.")
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(programs, key = { it.dosPath }) { program ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .selectable(
                                            selected = selectedProgram == program.dosPath,
                                            onClick = { selectedProgram = program.dosPath },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selectedProgram == program.dosPath, onClick = null)
                                    Text(
                                        program.dosPath,
                                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = programs.any { it.dosPath == selectedProgram },
                    onClick = {
                        val prepared = programContainer
                        val program = programs.firstOrNull { it.dosPath == selectedProgram }
                        if (prepared != null && program != null) {
                            try {
                                embedded.launchProgram(prepared, program)
                                programPreferences.edit().putString(game.id.toString(), program.dosPath).apply()
                                programGame = null
                            } catch (e: Exception) {
                                message = e.message ?: "Impossible de lancer ce programme."
                            }
                        }
                    }
                ) { Text("Lancer") }
            },
            dismissButton = {
                TextButton(onClick = { programGame = null }) { Text("Fermer") }
            }
        )
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

    diagnostic?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnostic = null },
            title = { Text("Dernier lancement") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Le bouton copie le rapport complet. Aperçu des dernières lignes :")
                    Text(report.takeLast(14000), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Retro ISO", report))
                    diagnosticCopied = true
                }) { Text(if (diagnosticCopied) "Copié !" else "Copier le rapport") }
            },
            dismissButton = {
                TextButton(onClick = { diagnostic = null }) { Text("Fermer") }
            }
        )
    }

    message?.let { currentMessage ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(currentMessage)
                }
            }
        )
    }
}

@Composable
fun GameCard(
    game: Game,
    onPlay: () -> Unit,
    onChooseProgram: () -> Unit,
    onPatch: () -> Unit,
    onInspectFiles: () -> Unit,
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
            OutlinedButton(onClick = onChooseProgram, modifier = Modifier.fillMaxWidth()) { Text("Choisir un programme") }
            OutlinedButton(onClick = onPatch, modifier = Modifier.fillMaxWidth()) { Text("Ajouter un patch") }
            TextButton(onClick = onInspectFiles, modifier = Modifier.fillMaxWidth()) {
                Text("Voir EXE / DLL")
            }
            if (!game.sourceUri.isNullOrBlank()) {
                TextButton(onClick = onReleaseCache, modifier = Modifier.fillMaxWidth()) {
                    Text("Libérer le cache local")
                }
            }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Supprimer") }
        }
    }
}

package com.elmagnifico.retroiso.runtime

import android.content.Context
import com.elmagnifico.retroiso.Game
import org.json.JSONObject
import java.io.File

/**
 * Point d'entrée unique du futur moteur Retro ISO.
 *
 * Cette couche prépare une configuration reproductible.
 * Les backends Wine/Box64/libcdio seront branchés derrière cette API.
 */
class RuntimeManager(private val context: Context) {

    data class PreparedRuntime(
        val profile: GameProfile,
        val container: ContainerConfig,
        val isoFile: File,
        val installer: LaunchConfig,
        val game: LaunchConfig
    )

    private data class CatalogEntry(
        val id: String,
        val asset: String,
        val aliases: Set<String>
    )

    private fun catalog(): List<CatalogEntry> {
        return try {
            val json = context.assets.open("profiles/index.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("profiles")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val aliases = o.getJSONArray("aliases")
                CatalogEntry(
                    id = o.getString("id"),
                    asset = o.getString("asset"),
                    aliases = (0 until aliases.length()).map { aliases.getString(it) }.toSet()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadProfileForName(name: String): GameProfile? {
        val key = slug(name)
        val entry = catalog().firstOrNull { key == it.id || key in it.aliases } ?: return null
        return try {
            context.assets.open(entry.asset).bufferedReader().use {
                GameProfile.fromJson(it.readText())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun prepare(game: Game, gameDir: File): PreparedRuntime {
        val profile = loadProfileForName(game.name)
            ?: throw IllegalStateException("Aucun profil runtime pour " + game.name)

        val iso = File(gameDir, profile.cdRom.image)
        if (profile.cdRom.required && !iso.isFile) {
            throw IllegalStateException("Image CD manquante : " + iso.absolutePath)
        }

        val container = ContainerConfig(
            name = "retroiso-" + profile.id,
            windowsVersion = profile.windows,
            architecture = profile.arch
        )

        return PreparedRuntime(
            profile = profile,
            container = container,
            isoFile = iso,
            installer = LaunchConfig(
                executable = profile.cdRom.drive + "\\" + profile.installer
            ),
            game = LaunchConfig(
                executable = profile.executable
            )
        )
    }

    private fun slug(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}

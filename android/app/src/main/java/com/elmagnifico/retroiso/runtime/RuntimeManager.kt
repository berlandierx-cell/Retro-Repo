package com.elmagnifico.retroiso.runtime

import android.content.Context
import com.elmagnifico.retroiso.Game
import java.io.File

/**
 * Point d'entrée unique du futur moteur Retro ISO.
 *
 * Important : cette classe ne prétend pas encore embarquer Wine/Box64.
 * Elle transforme un jeu + son profil en configuration reproductible.
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

    fun loadProfile(id: String): GameProfile? {
        return try {
            context.assets.open("profiles/$id.json").bufferedReader().use {
                GameProfile.fromJson(it.readText())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun prepare(game: Game, gameDir: File): PreparedRuntime {
        val profileId = slug(game.name)
        val profile = loadProfile(profileId)
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

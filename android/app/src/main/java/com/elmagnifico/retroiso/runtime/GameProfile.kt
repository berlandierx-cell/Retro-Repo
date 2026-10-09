package com.elmagnifico.retroiso.runtime

import org.json.JSONObject

data class GameProfile(
    val id: String,
    val name: String,
    val windows: String,
    val arch: String,
    val installer: String,
    val executable: String,
    val installedExecutablePath: String? = null,
    val language: String = "",
    val cdRom: CdRomConfig,
    val patchVersion: String? = null,
    val dllOverrides: Map<String, String> = emptyMap()
) {
    companion object {
        fun fromJson(json: String): GameProfile {
            val o = JSONObject(json)
            val cd = o.getJSONObject("cdrom")
            val dllOverrides = linkedMapOf<String, String>()
            val compat = o.optJSONObject("compatibility")
            val overrides = compat?.optJSONObject("dllOverrides")
            if (overrides != null) {
                val keys = overrides.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    dllOverrides[key] = overrides.optString(key)
                }
            }

            return GameProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                windows = o.optString("windows", "winxp"),
                arch = o.optString("arch", "x86"),
                installer = o.getString("installer"),
                executable = o.getString("executable"),
                installedExecutablePath = o.optString("installedExecutablePath").takeIf { it.isNotBlank() },
                language = o.optString("language"),
                cdRom = CdRomConfig(
                    required = cd.optBoolean("required", false),
                    drive = cd.optString("drive", "X:"),
                    image = cd.optString("image", "disc.iso")
                ),
                patchVersion = o.optString("patch").takeIf { it.isNotBlank() },
                dllOverrides = dllOverrides
            )
        }
    }
}

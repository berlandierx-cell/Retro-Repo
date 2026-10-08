package com.elmagnifico.retroiso.runtime

import org.json.JSONObject

data class GameProfile(
    val id: String,
    val name: String,
    val windows: String,
    val arch: String,
    val installer: String,
    val executable: String,
    val cdRom: CdRomConfig,
    val patchVersion: String? = null
) {
    companion object {
        fun fromJson(json: String): GameProfile {
            val o = JSONObject(json)
            val cd = o.getJSONObject("cdrom")
            return GameProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                windows = o.optString("windows", "winxp"),
                arch = o.optString("arch", "x86"),
                installer = o.getString("installer"),
                executable = o.getString("executable"),
                cdRom = CdRomConfig(
                    required = cd.optBoolean("required", false),
                    drive = cd.optString("drive", "X:"),
                    image = cd.optString("image", "disc.iso")
                ),
                patchVersion = o.optString("patch").takeIf { it.isNotBlank() }
            )
        }
    }
}

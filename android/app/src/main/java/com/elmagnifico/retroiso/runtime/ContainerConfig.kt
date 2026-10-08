package com.elmagnifico.retroiso.runtime

data class ContainerConfig(
    val name: String,
    val windowsVersion: String,
    val architecture: String,
    val screenWidth: Int = 1280,
    val screenHeight: Int = 720,
    val graphicsBackend: String = "auto",
    val audioBackend: String = "alsa"
)

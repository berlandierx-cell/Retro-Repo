package com.elmagnifico.retroiso.runtime

data class LaunchConfig(
    val executable: String,
    val workingDirectory: String? = null,
    val arguments: List<String> = emptyList()
)

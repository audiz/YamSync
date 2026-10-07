package io.github.audiz.models

import kotlinx.serialization.Serializable

@Serializable
data class ThematicWavePreset(
    val title: String,
    val seeds: List<String>,
    val subTitle: String? = null
)

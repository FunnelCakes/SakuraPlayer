package com.sakura.player.player

data class PlayerConfig(
    val mode: PlayerMode,
    val title: String = "",
    val episodes: List<EpisodeItem> = emptyList(),
    val coverUrl: String = ""
)

enum class PlayerMode { INLINE, FULLSCREEN }

data class EpisodeItem(
    val index: Int,
    val name: String,
    val path: String = "",       // local file path
    val videoId: Long = 0,       // online video id
    val isLocal: Boolean = false
)

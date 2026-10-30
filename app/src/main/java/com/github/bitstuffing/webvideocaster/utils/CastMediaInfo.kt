package com.github.bitstuffing.webvideocaster.utils

data class CastMediaInfo(
    val playerState: CastConnectionManager.CastPlayerState,
    val currentTime: Double = 0.0,
    val duration: Double = -1.0,
    val streamType: String? = null,
    val contentType: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val imageUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val activeTrackIds: List<Int> = emptyList(),
    val volume: Double = 1.0,
    val isMuted: Boolean = false
) {
    val isLive: Boolean
        get() = streamType == "LIVE"

    val resolution: String?
        get() = if (width > 0 && height > 0) {
            "${height}p"
        } else null
}
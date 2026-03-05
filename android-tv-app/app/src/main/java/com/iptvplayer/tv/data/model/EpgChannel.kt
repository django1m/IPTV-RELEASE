package com.iptvplayer.tv.data.model

data class EpgChannel(
    val stream: LiveStream,
    val programs: List<EpgProgram>
)

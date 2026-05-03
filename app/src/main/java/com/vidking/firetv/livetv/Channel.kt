package com.vidking.firetv.livetv

data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val group: String?
)

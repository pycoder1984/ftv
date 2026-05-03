package com.vidking.firetv.livetv

object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(content: String): List<Channel> {
        val lines = content.replace("\r\n", "\n").replace("\r", "\n").lines()
        val channels = mutableListOf<Channel>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val attrs = attrRegex.findAll(line)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val name = line.substringAfterLast(',').trim()
                    .takeIf { it.isNotEmpty() } ?: attrs["tvg-name"]
                if (name != null) {
                    val id = attrs["tvg-id"]?.takeIf { it.isNotEmpty() }
                        ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                    val logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }

                    // Skip optional metadata lines (#EXTVLCOPT etc.) to reach the URL
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                    val url = lines.getOrNull(j)?.trim() ?: ""
                    if (url.startsWith("http")) {
                        channels += Channel(id, name, logo, url, group)
                        i = j + 1
                        continue
                    }
                }
            }
            i++
        }
        return channels
    }
}

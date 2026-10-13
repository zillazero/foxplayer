package site.pk187.foxplayer.parse

import java.io.BufferedReader
import java.io.InputStream

/** Ein Eintrag aus einer M3U-Liste. */
data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val catchupDays: Int,
    val catchupSource: String,
    val userAgent: String?,
)

/**
 * Streamender M3U/M3U8-Parser (eigene Implementierung, angelehnt an das Verhalten des
 * M3UAndroid-Parsers: #EXTINF-Attribute, #EXTGRP, #EXTVLCOPT http-user-agent).
 * Liest zeilenweise, haelt nie die ganze Liste im Speicher.
 */
object M3uParser {
    fun parse(input: InputStream, onEntry: (M3uEntry) -> Unit) {
        val reader: BufferedReader = BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var title = ""
        var attrs: Map<String, String> = emptyMap()
        var extGroup = ""
        var ua: String? = null
        var pending = false
        while (true) {
            var line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            if (line[0] == '\uFEFF') line = line.substring(1)
            line = line.trim()
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                when {
                    line.startsWith("#EXTINF", true) -> {
                        val body = line.substringAfter(':', "")
                        val comma = indexOfUnquoted(body, ',')
                        val prefix = if (comma >= 0) body.substring(0, comma) else body
                        title = if (comma >= 0) body.substring(comma + 1).trim() else ""
                        attrs = parseAttributes(prefix)
                        pending = true
                    }
                    line.startsWith("#EXTGRP:", true) -> extGroup = line.substring(8).trim()
                    line.startsWith("#EXTVLCOPT:", true) -> {
                        val opt = line.substring(11)
                        if (opt.startsWith("http-user-agent=", true)) ua = opt.substringAfter('=').trim()
                    }
                }
                continue
            }
            if (!pending && attrs.isEmpty()) {
                // nackte URL ohne #EXTINF
            }
            val name = title.ifBlank { attrs["tvg-name"].orEmpty() }
                .ifBlank { line.substringAfterLast('/').substringBefore('?') }
            onEntry(
                M3uEntry(
                    name = name,
                    url = line,
                    group = (attrs["group-title"] ?: "").ifBlank { extGroup },
                    logo = attrs["tvg-logo"] ?: attrs["logo"] ?: "",
                    tvgId = (attrs["tvg-id"] ?: "").ifBlank { attrs["tvg-name"] ?: name },
                    catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"])?.toIntOrNull() ?: 0,
                    catchupSource = attrs["catchup-source"] ?: "",
                    userAgent = ua ?: attrs["user-agent"] ?: attrs["http-user-agent"],
                )
            )
            title = ""; attrs = emptyMap(); extGroup = ""; ua = null; pending = false
        }
    }

    internal fun parseAttributes(s: String): Map<String, String> {
        val out = HashMap<String, String>(8)
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i] == ' ' || s[i] == ',' || s[i] == '\t')) i++
            val ks = i
            while (i < n && s[i] != '=' && s[i] != ' ') i++
            if (i >= n || s[i] != '=') { continue }
            val key = s.substring(ks, i).lowercase()
            i++
            val value: String
            if (i < n && s[i] == '"') {
                val end = s.indexOf('"', i + 1).let { if (it < 0) n else it }
                value = s.substring(i + 1, end); i = end + 1
            } else {
                val vs = i
                while (i < n && s[i] != ' ' && s[i] != ',') i++
                value = s.substring(vs, i)
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun indexOfUnquoted(s: String, c: Char): Int {
        var q = false
        for (i in s.indices) {
            val ch = s[i]
            if (ch == '"') q = !q else if (ch == c && !q) return i
        }
        return -1
    }
}

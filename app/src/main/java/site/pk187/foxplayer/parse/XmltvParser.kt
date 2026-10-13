package site.pk187.foxplayer.parse

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

data class XmltvProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val desc: String)

/**
 * Streamender XMLTV-Parser (Pull-Parser, Ansatz wie EpgParserImpl in M3UAndroid, GPL-3.0).
 * Nur Sendungen im Fenster [from, to) werden geliefert; channelFilter (falls gesetzt) begrenzt
 * auf bekannte tvg-ids, damit bei grossen EPGs nichts Unnoetiges in die Datenbank kommt.
 */
object XmltvParser {
    fun parse(
        input: InputStream,
        from: Long,
        to: Long,
        channelFilter: Set<String>?,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                val ch = p.getAttributeValue(null, "channel") ?: ""
                val start = parseTime(p.getAttributeValue(null, "start"))
                val stop = parseTime(p.getAttributeValue(null, "stop"))
                val wanted = stop > from && start < to && (channelFilter == null || ch in channelFilter)
                var title = ""; var desc = ""
                var depth = 1
                while (depth > 0) {
                    ev = p.next()
                    if (ev == XmlPullParser.START_TAG) {
                        depth++
                        if (wanted && depth == 2) when (p.name) {
                            "title" -> if (title.isEmpty()) { title = p.nextText(); depth-- }
                            "desc" -> if (desc.isEmpty()) { desc = p.nextText(); depth-- }
                        }
                    } else if (ev == XmlPullParser.END_TAG) depth--
                    else if (ev == XmlPullParser.END_DOCUMENT) return
                }
                if (wanted) onProgramme(XmltvProgramme(ch, start, stop, title, desc))
            }
            ev = p.next()
        }
    }

    /** "20261013143000 +0200" -> epoch ms; schnell, ohne java.time-Formatter. */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 14) return 0L
        fun n(a: Int, b: Int): Int { var r = 0; for (i in a until b) r = r * 10 + (s[i] - '0'); return r }
        val y = n(0, 4); val mo = n(4, 6); val d = n(6, 8); val h = n(8, 10); val mi = n(10, 12); val se = n(12, 14)
        var offMin = 0
        val tz = s.substring(14).trim()
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            offMin = ((tz[1] - '0') * 10 + (tz[2] - '0')) * 60 + (tz[3] - '0') * 10 + (tz[4] - '0')
            if (tz[0] == '-') offMin = -offMin
        }
        val days = daysFromCivil(y, mo, d)
        return ((days * 86400L + h * 3600L + mi * 60L + se) - offMin * 60L) * 1000L
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}

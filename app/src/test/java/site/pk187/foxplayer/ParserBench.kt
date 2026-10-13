package site.pk187.foxplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import site.pk187.foxplayer.parse.M3uParser
import site.pk187.foxplayer.parse.XmltvParser
import java.io.File
import java.util.zip.GZIPInputStream

class ParserTest {
    @Test fun attributes() {
        val m = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a.de\" tvg-logo=\"http://l/x.png\" group-title=\"News, DE\" catchup-days=\"7\",Das Erste HD\nhttp://s/1.m3u8\n"
        val out = mutableListOf<site.pk187.foxplayer.parse.M3uEntry>()
        M3uParser.parse(m.byteInputStream()) { out += it }
        assertEquals(1, out.size)
        assertEquals("Das Erste HD", out[0].name); assertEquals("News, DE", out[0].group)
        assertEquals("a.de", out[0].tvgId); assertEquals(7, out[0].catchupDays)
    }

    @Test fun time() {
        assertEquals(java.time.ZonedDateTime.parse("2026-10-13T14:30:00+02:00").toInstant().toEpochMilli(), XmltvParser.parseTime("20261013143000 +0200"))
    }

    private fun bench(name: String, runs: Int, block: () -> Int): Pair<Int, Long> {
        var n = 0
        repeat(3) { n = block() } // Aufwaermen
        val times = (1..runs).map { val t = System.nanoTime(); n = block(); (System.nanoTime() - t) / 1_000_000 }.sorted()
        val med = times[times.size / 2]
        println("BENCH $name: $n Eintraege, Median $med ms (min ${times.first()}, max ${times.last()})")
        return n to med
    }

    @Test fun benchmark() {
        if (System.getProperty("bench") == null) return
        val m3u = File("/workspace/fox-apps/iptv-org-index.m3u")
        val (fox, _) = bench("FOX M3U iptv-org", 15) { var c = 0; m3u.inputStream().use { M3uParser.parse(it) { c++ } }; c }
        val (ref, _) = bench("M3UAndroid M3U iptv-org", 15) { site.pk187.foxplayer.bench.RefBench.m3uAndroid(m3u) }
        println("BENCH Kanaele FOX=$fox M3UAndroid=$ref")
        val epg = File("/workspace/epg-de.xml.gz")
        bench("FOX XMLTV epg-de (alles)", 5) {
            var c = 0; GZIPInputStream(epg.inputStream(), 65536).use { XmltvParser.parse(it, 0, Long.MAX_VALUE, null) { c++ } }; c
        }
        assertTrue(fox > 10000)
    }
}

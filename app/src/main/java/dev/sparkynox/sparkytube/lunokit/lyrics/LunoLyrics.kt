package dev.sparkynox.sparkytube.lunokit.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class LyricWord(val text: String, val startMs: Long, val endMs: Long)

// words is null for line-synced lyrics, filled when the source has per-word timing
class LyricLine(val timeMs: Long, val text: String, var endMs: Long = 0L, val words: List<LyricWord>? = null)

class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    // line being sung at posMs, -1 before the first one
    fun indexAt(posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

object LunoLyrics {

    private const val BASE = "https://lrclib.net/api/search"
    private const val BETTER = "https://lyrics-api.boidu.dev/getLyrics"
    private const val UA = "SparkyTube/2.3 (https://github.com/sparkynox/SparkyTube)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // null value = looked and found nothing (this session only)
    private val mem = LinkedHashMap<String, Lyrics?>()

    suspend fun get(ctx: Context, videoId: String?, title: String, channel: String?, durationMs: Long): Lyrics? {
        val key = "${videoId.orEmpty()}|$title"
        synchronized(mem) { if (mem.containsKey(key)) return mem[key] }

        val dir = File(ctx.cacheDir, "lunolyrics").apply { mkdirs() }
        val file = File(dir, "${videoId.orEmpty()}_${key.hashCode().toString(16)}.json")

        val result = withContext(Dispatchers.IO) {
            readDisk(file, durationMs) ?: withTimeoutOrNull(14_000) {
                coroutineScope {
                    // both at once: per-word lyrics win if they show up in time, line-synced is the fallback
                    val better = async { betterHit(title, channel, durationMs) }
                    val lrc = async { search(title, channel, durationMs) }
                    val hit = withTimeoutOrNull(7_000) { better.await() } ?: lrc.await()
                    if (hit != null) writeDisk(file, hit)
                    hit?.let { toLyrics(it, durationMs) }
                }
            }
        }

        synchronized(mem) {
            if (mem.size >= 40) mem.remove(mem.keys.first())
            mem[key] = result
        }
        return result
    }

    private fun search(title: String, channel: String?, durMs: Long): JSONObject? {
        val q = LyricsQuery.build(title, channel)
        val sec = if (durMs > 0) (durMs / 1000).toInt() else 0
        for ((track, artist) in q.pairs) {
            pick(call("track_name" to track, "artist_name" to artist), sec)?.let { return it }
        }
        return pick(call("q" to q.free), sec)
    }

    private fun call(vararg params: Pair<String, String>): JSONArray? {
        val b = BASE.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> if (v.isNotBlank()) b.addQueryParameter(k, v) }
        val req = Request.Builder().url(b.build()).header("User-Agent", UA).build()
        return try {
            http.newCall(req).execute().use { if (it.isSuccessful) JSONArray(it.body?.string() ?: "[]") else null }
        } catch (e: Exception) {
            null
        }
    }

    // org.json gives "null" the string for a JSON null, so go through isNull first
    private fun str(o: JSONObject, key: String) = if (o.isNull(key)) "" else o.optString(key)

    private fun pick(arr: JSONArray?, sec: Int): JSONObject? {
        arr ?: return null
        var best: JSONObject? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("instrumental")) continue
            val synced = str(o, "syncedLyrics").isNotBlank()
            if (!synced && str(o, "plainLyrics").isBlank()) continue
            val diff = if (sec > 0) abs(o.optDouble("duration", 0.0) - sec).toInt() else 0
            if (sec > 0 && diff > 8) continue // different cut of the song, timing would be off
            val score = diff * 2 + if (synced) 0 else 100
            if (score < bestScore) { best = o; bestScore = score }
        }
        return best
    }

    private fun toLyrics(o: JSONObject, durMs: Long): Lyrics? {
        val ttml = str(o, "ttml")
        if (ttml.isNotBlank()) parseTtml(ttml)?.let { return it }
        val lrc = str(o, "syncedLyrics")
        if (lrc.isNotBlank()) parseLrc(lrc)?.let { return it }
        return fromPlain(str(o, "plainLyrics"), durMs)
    }

    private fun readDisk(f: File, durMs: Long): Lyrics? = try {
        if (f.exists()) toLyrics(JSONObject(f.readText()), durMs) else null
    } catch (e: Exception) {
        null
    }

    private fun writeDisk(f: File, o: JSONObject) {
        try {
            val slim = JSONObject()
                .put("ttml", str(o, "ttml"))
                .put("syncedLyrics", str(o, "syncedLyrics"))
                .put("plainLyrics", str(o, "plainLyrics"))
            f.writeText(slim.toString())
        } catch (_: Exception) {
        }
    }

    private fun betterHit(title: String, channel: String?, durMs: Long): JSONObject? {
        val sec = if (durMs > 0) (durMs / 1000).toInt() else 0
        val tried = HashSet<Pair<String, String>>()
        for ((song, artist) in LyricsQuery.build(title, channel).pairs) {
            // this one wants a real artist, a guess just returns the wrong song
            if (artist.isBlank() || !tried.add(song to artist)) continue
            val url = BETTER.toHttpUrl().newBuilder().addQueryParameter("s", song).addQueryParameter("a", artist)
            if (sec > 0) url.addQueryParameter("d", sec.toString())
            val req = Request.Builder().url(url.build()).header("User-Agent", UA).build()
            val ttml = try {
                http.newCall(req).execute().use { if (it.isSuccessful) str(JSONObject(it.body?.string() ?: "{}"), "ttml") else "" }
            } catch (e: Exception) {
                ""
            }
            if (ttml.isNotBlank() && parseTtml(ttml) != null) return JSONObject().put("ttml", ttml)
        }
        return null
    }

    // "1:02.5", "00:01:02.500", "62.5s" -> ms
    private fun ttmlTime(s: String): Long {
        var sec = 0.0
        for (part in s.trim().removeSuffix("s").split(":")) sec = sec * 60 + (part.toDoubleOrNull() ?: return 0L)
        return (sec * 1000).toLong()
    }

    private fun attr(xp: XmlPullParser, name: String): String {
        for (i in 0 until xp.attributeCount) {
            val n = xp.getAttributeName(i)
            if (n == name || n.endsWith(":$name")) return xp.getAttributeValue(i)
        }
        return ""
    }

    private class Tok(val text: String, val start: Long, val end: Long, var space: Boolean = false)

    fun parseTtml(ttml: String): Lyrics? = try {
        readTtml(ttml)
    } catch (e: Exception) {
        null
    }

    private fun readTtml(ttml: String): Lyrics? {
        val xp = android.util.Xml.newPullParser()
        xp.setInput(StringReader(ttml))

        val lines = ArrayList<LyricLine>()
        var inP = false
        var pBegin = 0L
        var pEnd = 0L
        var toks = ArrayList<Tok>()
        var plain = StringBuilder()
        var skip = 0 // inside a background / translation / romanization span
        var word: StringBuilder? = null
        var wB = 0L
        var wE = 0L

        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (xp.name) {
                    "p" -> {
                        inP = true
                        toks = ArrayList()
                        plain = StringBuilder()
                        pBegin = ttmlTime(attr(xp, "begin"))
                        pEnd = ttmlTime(attr(xp, "end"))
                    }
                    "span" -> if (inP) {
                        if (skip > 0) {
                            skip++
                        } else {
                            val role = attr(xp, "role")
                            if (role == "x-bg" || role == "x-translation" || role == "x-roman") {
                                skip = 1
                            } else if (attr(xp, "begin").isNotEmpty() && attr(xp, "end").isNotEmpty()) {
                                word = StringBuilder()
                                wB = ttmlTime(attr(xp, "begin"))
                                wE = ttmlTime(attr(xp, "end"))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inP && skip == 0) {
                    val t = xp.text
                    val w = word
                    if (w != null) {
                        w.append(t)
                    } else if (t.isBlank()) {
                        // whitespace between two spans means a word break
                        toks.lastOrNull()?.space = true
                    } else {
                        plain.append(t)
                    }
                }
                XmlPullParser.END_TAG -> when (xp.name) {
                    "span" -> if (inP) {
                        if (skip > 0) {
                            skip--
                        } else {
                            val w = word
                            if (w != null) {
                                val txt = w.toString().trim()
                                if (txt.isNotEmpty()) toks.add(Tok(txt, wB, wE, w.endsWith(" ")))
                                word = null
                            }
                        }
                    }
                    "p" -> if (inP) {
                        inP = false
                        if (toks.isNotEmpty()) {
                            // syllables with no gap between them are one word
                            val words = ArrayList<LyricWord>()
                            var cur: StringBuilder? = null
                            var cs = 0L
                            var ce = 0L
                            for (t in toks) {
                                if (cur == null) { cur = StringBuilder(t.text); cs = t.start; ce = t.end }
                                else { cur.append(t.text); ce = t.end }
                                if (t.space) { words.add(LyricWord(cur.toString(), cs, ce)); cur = null }
                            }
                            cur?.let { words.add(LyricWord(it.toString(), cs, ce)) }
                            val start = if (pBegin > 0) pBegin else words.first().startMs
                            lines.add(LyricLine(start, words.joinToString(" ") { it.text }, maxOf(pEnd, words.last().endMs), words))
                        } else if (plain.isNotBlank()) {
                            lines.add(LyricLine(pBegin, plain.toString().trim(), if (pEnd > pBegin) pEnd else pBegin + 3000))
                        }
                    }
                }
            }
            ev = xp.next()
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.timeMs }
        return Lyrics(lines, true)
    }

    private val TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(lrc: String): Lyrics? {
        val raw = ArrayList<LyricLine>()
        for (row in lrc.lineSequence()) {
            val tags = TAG.findAll(row).toList()
            if (tags.isEmpty()) continue
            val text = row.substring(tags.last().range.last + 1).trim()
            for (t in tags) {
                val min = t.groupValues[1].toLong()
                val sec = t.groupValues[2].toLong()
                val frac = t.groupValues[3]
                val ms = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                raw.add(LyricLine((min * 60 + sec) * 1000 + ms, text))
            }
        }
        if (raw.isEmpty()) return null
        raw.sortBy { it.timeMs }
        // a long instrumental gap shouldn't stretch the highlight, so cap it by how long the line takes to sing
        for (i in raw.indices) {
            val next = raw.getOrNull(i + 1)?.timeMs ?: (raw[i].timeMs + 6000)
            val sung = (raw[i].text.length * 130L + 700L).coerceAtMost(9000L)
            raw[i].endMs = minOf(next - 120, raw[i].timeMs + sung).coerceAtLeast(raw[i].timeMs + 400)
        }
        val lines = raw.filter { it.text.isNotBlank() }
        return if (lines.isEmpty()) null else Lyrics(lines, true)
    }

    // no timestamps: spread the lines over the song so the view still follows along roughly
    private fun fromPlain(plain: String, durMs: Long): Lyrics? {
        val rows = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        val total = if (durMs > 0) durMs else rows.size * 4000L
        val step = total / rows.size
        return Lyrics(rows.mapIndexed { i, t -> LyricLine(i * step, t, (i + 1) * step) }, false)
    }
}

object LyricsQuery {

    class Q(val pairs: List<Pair<String, String>>, val free: String)

    private val BRACKETS = Regex("""(?i)\s*[(\[【][^)\]】]*(official|lyric|audio|video|visuali[sz]er|\bmv\b|\bhd\b|4k|remaster|explicit|clip|sub|letra)[^)\]】]*[)\]】]""")
    private val FEAT = Regex("""(?i)\s+(feat\.?|ft\.?)\s+.*$""")
    private val BAR_TAIL = Regex("""\s*[|｜]\s*.*$""")
    private val DASH = Regex("""\s[-–—]\s""")

    private fun clean(s: String) = s.replace(BRACKETS, "").replace(BAR_TAIL, "").replace(FEAT, "").trim()

    private fun cleanChannel(s: String?) = (s ?: "")
        .replace(Regex("(?i)\\s*-\\s*topic$"), "")
        .replace(Regex("(?i)vevo$"), "")
        .replace(Regex("(?i)\\s*official$"), "")
        .trim()

    fun build(title: String, channel: String?): Q {
        val t = clean(title)
        val ch = cleanChannel(channel)
        val pairs = ArrayList<Pair<String, String>>()

        // "Artist - Song" is by far the most common upload title
        val dash = DASH.find(t)
        if (dash != null) {
            val artist = t.substring(0, dash.range.first).trim()
            val song = t.substring(dash.range.last + 1).trim()
            if (artist.isNotEmpty() && song.isNotEmpty()) pairs.add(song to artist)
        }
        if (ch.isNotEmpty()) pairs.add(t to ch)
        pairs.add(t to "")
        return Q(pairs.distinct(), if (ch.isNotEmpty()) "$ch $t" else t)
    }
}

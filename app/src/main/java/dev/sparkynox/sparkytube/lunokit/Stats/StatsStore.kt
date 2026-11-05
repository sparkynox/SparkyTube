package dev.sparkynox.sparkytube.lunokit.stats

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// local watch stats, nothing leaves the phone
object StatsStore {

    private const val PREFS = "sparkytube_stats"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    class Video(val id: String, val title: String, val channel: String, val ms: Long)
    class Day(val label: String, val ms: Long)
    class Snapshot(
        val totalMs: Long,
        val musicMs: Long,
        val videos: Int,
        val topVideos: List<Video>,
        val topChannels: List<Pair<String, Long>>,
        val week: List<Day>,
    )

    fun add(ctx: Context, videoId: String, title: String, channel: String, ms: Long, music: Boolean) {
        if (ms <= 0) return
        val sp = p(ctx)
        val ed = sp.edit()
        ed.putLong("total", sp.getLong("total", 0) + ms)
        if (music) ed.putLong("music", sp.getLong("music", 0) + ms)
        val dk = "d_" + dayFmt.format(Date())
        ed.putLong(dk, sp.getLong(dk, 0) + ms)
        val vk = "v_$videoId"
        val old = sp.getString(vk, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ed.putString(
            vk,
            JSONObject().put("t", title).put("c", channel).put("ms", (old?.optLong("ms") ?: 0L) + ms).toString()
        )
        ed.apply()
    }

    fun snapshot(ctx: Context): Snapshot {
        val sp = p(ctx)
        val vids = ArrayList<Video>()
        for ((k, v) in sp.all) {
            if (!k.startsWith("v_") || v !is String) continue
            val o = runCatching { JSONObject(v) }.getOrNull() ?: continue
            vids.add(Video(k.removePrefix("v_"), o.optString("t"), o.optString("c"), o.optLong("ms")))
        }
        val channels = vids.filter { it.channel.isNotBlank() }
            .groupBy { it.channel }
            .map { (c, l) -> c to l.sumOf { it.ms } }
            .sortedByDescending { it.second }
            .take(5)

        val cal = Calendar.getInstance()
        val days = ArrayList<Day>()
        for (i in 6 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = SimpleDateFormat("EEE", Locale.getDefault()).format(c.time).take(2)
            days.add(Day(label, sp.getLong("d_" + dayFmt.format(c.time), 0)))
        }
        return Snapshot(
            totalMs = sp.getLong("total", 0),
            musicMs = sp.getLong("music", 0),
            videos = vids.size,
            topVideos = vids.sortedByDescending { it.ms }.take(5),
            topChannels = channels,
            week = days,
        )
    }

    fun reset(ctx: Context) = p(ctx).edit().clear().apply()
}

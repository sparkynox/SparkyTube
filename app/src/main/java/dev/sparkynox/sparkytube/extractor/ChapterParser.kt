package dev.sparkynox.sparkytube.extractor

data class Chapter(val title: String, val startMs: Long)

object ChapterParser {

    // matches "0:00 Intro", "1:23:45 - Something", "02:30 | Title" etc,
    // timestamp at start of a line, then any separator, then the title
    private val lineRegex = Regex("""^\s*(?:\d{1,2}:)?\d{1,2}:\d{2}""")
    private val fullRegex = Regex("""^\s*((?:\d{1,2}:)?\d{1,2}:\d{2})\s*[-–—|:.)\]]*\s*(.+?)\s*$""")

    /**
     * YouTube only treats a description as having chapters if the first
     * timestamp is 0:00 and there are at least 3 of them, so this
     * copies that rule -- otherwise random timestamps mentioned in a
     * comment-style description ("see 5:30 for the funny part") would
     * turn into fake chapters.
     */
    fun parse(description: String?): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()

        val found = description.lines().mapNotNull { line ->
            if (!lineRegex.containsMatchIn(line)) return@mapNotNull null
            val m = fullRegex.find(line) ?: return@mapNotNull null
            val ms = toMs(m.groupValues[1]) ?: return@mapNotNull null
            val title = m.groupValues[2].ifBlank { return@mapNotNull null }
            Chapter(title, ms)
        }

        if (found.size < 3) return emptyList()
        if (found.first().startMs != 0L) return emptyList()
        // must be in ascending order, otherwise it's not really a chapter list
        if (found.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return emptyList()
        return found
    }

    private fun toMs(stamp: String): Long? {
        val parts = stamp.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000
            else -> null
        }
    }
}

package eu.kanade.tachiyomi.animeextension.en.anilist

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

fun buildValidFilename(origName: String): String {
    val name = origName.trim('.', ' ')
    if (name.isEmpty()) {
        return "(invalid)"
    }
    val sb = StringBuilder(name.length)
    name.forEach { c ->
        if (isValidFatFilenameChar(c)) {
            sb.append(c)
        }
    }
    // Even though vfat allows 255 UCS-2 chars, we might eventually write to
    // ext4 through a FUSE layer, so use that limit minus 15 reserved characters.
    return sb.toString().take(240)
}

private fun isValidFatFilenameChar(c: Char): Boolean {
    if (0x00.toChar() <= c && c <= 0x1f.toChar()) {
        return false
    }
    return when (c) {
        '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
        else -> true
    }
}

fun Double.equalsTo(other: Float): Boolean {
    return abs(this - other) < 0.0001
}

fun String.trimInfo(): String {
    return this.replace(TRIM_REGEX, "").replace(SEASON_REGEX, "")
}

fun parseDate(dateStr: String): Long {
    return try {
        DATE_FORMAT.parse(dateStr)!!.time
    } catch (_: ParseException) {
        0L
    }
}

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
private val SEASON_REGEX = Regex("""S\d+""")
private val TRIM_REGEX = Regex("""^[\[\(].*?[\]\)]""")
val VALID_EXTENSIONS = listOf("avi", "flv", "mkv", "mov", "mp4", "webm", "wmv")

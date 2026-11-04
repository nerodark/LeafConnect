package leafconnect

import java.util.Locale
import kotlin.math.abs

/**
 * Converts the Nissan API's DMS (degree/minute/second) coordinate strings to
 * decimal degrees, for the geo: URI used by openInMaps.
 *
 * The API sends the 4-segment form, e.g. "49:13:57:33" — the 4th segment is
 * the fraction of a second (hundredths), so the value is
 * 49 + 13/60 + 57/3600 + 33/360000. The old code split on ":" and treated
 * parts[2] as minutes, so "49:13:57:33" produced 49 + 57/60 = 50.0 and every
 * locate opened a point ~111 km north of the car.
 *
 * Handles the 3-segment form, the leading-minus form (-123:09:08:44), the
 * trailing letter form (49:13:57:33N), spaces, and plain decimals.
 */
object GeoCoordinatesParser {

    /** Decimal degrees, or null when the string is not a usable coordinate. */
    fun toDecimal(raw: String): Double? {
        var s = raw.trim().replace(" ", "")
        if (s.isEmpty()) return null

        var sign = 1.0
        // Direction letters, trailing (49:13:57N) or leading (S 49:13:57).
        val last = s.last().uppercase()
        if (last in "NSEW") {
            if (last == "S" || last == "W") sign = -1.0
            s = s.dropLast(1)
        }
        val first = s.firstOrNull()
        if (first == 'N' || first == 'S' || first == 'E' || first == 'W') {
            if (first == 'S' || first == 'W') sign = -1.0
            s = s.drop(1)
        }
        if (s.isEmpty()) return null

        val parts = s.split(":").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        if (parts.size == 1) {
            val degOnly = parts[0].toDoubleOrNull() ?: return null
            return sign * degOnly
        }

        val deg = parts[0].toDoubleOrNull() ?: return null
        val min = parts[1].toDoubleOrNull() ?: return null
        val sec = if (parts.size >= 3) parts[2].toDoubleOrNull() ?: return null else 0.0
        // 4th segment = fraction of the second (hundredths), NOT milliseconds.
        val frac = if (parts.size >= 4) parts[3].toDoubleOrNull() ?: return null else 0.0

        val value = abs(deg) + min / 60.0 + sec / 3600.0 + frac / 360000.0
        if (deg < 0) sign = -1.0
        if (!value.isFinite()) return null
        return sign * value
    }

    /** Decimal string with a '.' separator (Locale.US), for geo: URIs. */
    fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
}

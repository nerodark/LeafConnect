package leafconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoCoordinatesParserTest {

    private fun decimal(raw: String) = GeoCoordinatesParser.toDecimal(raw)

    @Test
    fun parsesFourSegmentDms() {
        // The form the Nissan API sends: 4th segment = fraction of a second.
        val lat = decimal("49:13:57:33")
        assertEquals(49.2325916667, lat!!, 1e-6)
    }

    @Test
    fun parsesNegativeFourSegmentLongitude() {
        val lon = decimal("-123:09:08:44")
        assertEquals(-123.1523444444, lon!!, 1e-6)
    }

    @Test
    fun parsesThreeSegmentDms() {
        assertEquals(49.2325, decimal("49:13:57")!!, 1e-6)
    }

    @Test
    fun parsesDirectionLetters() {
        assertEquals(49.2325, decimal("49:13:57N")!!, 1e-6)
        assertEquals(-49.2325, decimal("49:13:57S")!!, 1e-6)
        assertEquals(-123.15, decimal("123:09W")!!, 1e-6)
    }

    @Test
    fun parsesPlainDecimal() {
        assertEquals(49.2325, decimal("49.2325")!!, 1e-6)
        assertEquals(-123.15, decimal("-123.15")!!, 1e-6)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(decimal(""))
        assertNull(decimal("no-location"))
        assertNull(decimal("49:13:x:33"))
    }

    @Test
    fun formatsWithDotSeparator() {
        // Locale-safe for geo: URIs — a comma decimal separator breaks the URI.
        assertEquals("49.232592", GeoCoordinatesParser.format(49.2325916667))
    }
}

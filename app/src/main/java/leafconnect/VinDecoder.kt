package leafconnect

/**
 * VinDecoder.kt
 *
 * Offline VIN (Vehicle Identification Number) decoder. No dependencies.
 *
 * Decodes: structure, region/country, manufacturer (small built-in WMI table),
 * model year, plant code, serial number, and validates the ISO 3779 check digit
 * (mandatory for North America, optional elsewhere).
 *
 * Note: model/trim/engine (VDS, positions 4-8) are manufacturer-specific and can't be
 * decoded offline. For those, use the NHTSA vPIC API (see bottom of file).
 */

data class VinInfo(
    val vin: String,
    val wmi: String,
    val vds: String,
    val vis: String,
    val region: String,
    val country: String?,
    val manufacturer: String?,
    val modelYearCandidates: List<Int>,
    val modelYear: Int?,
    val plantCode: Char,
    val serialNumber: String,
    val checkDigit: Char,
    val checkDigitValid: Boolean,
    val isSmallManufacturer: Boolean, // WMI 3rd char '9' => fewer than 500 vehicles/year
)

sealed class VinResult {
    data class Success(val info: VinInfo) : VinResult()
    data class Failure(val reason: String) : VinResult()
}

object VinDecoder {

    private val VIN_REGEX = Regex("^[A-HJ-NPR-Z0-9]{17}$") // I, O, Q are never allowed

    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    private val TRANSLITERATION: Map<Char, Int> = buildMap {
        for (d in '0'..'9') put(d, d - '0')
        val letters = mapOf(
            'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
            'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
            'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
        )
        putAll(letters)
    }

    /** Model year codes (position 10). Cycles every 30 years; I, O, Q, U, Z, 0 unused. */
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /** A small, non-exhaustive WMI lookup. Extend as needed. */
    private val WMI_TABLE: Map<String, String> = mapOf(
        "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford",
        "1G1" to "Chevrolet", "1GC" to "Chevrolet",
        "1GT" to "GMC", "1GY" to "Cadillac",
        "1HG" to "Honda", "2HG" to "Honda", "JHM" to "Honda",
        "19X" to "Honda", "5FN" to "Honda",
        "1C4" to "Chrysler", "1C3" to "Chrysler",
        "1J4" to "Jeep", "1N4" to "Nissan", "JN1" to "Nissan",
        "1YV" to "Mazda", "JM1" to "Mazda",
        "2T1" to "Toyota", "4T1" to "Toyota", "5TD" to "Toyota",
        "JTD" to "Toyota", "JT2" to "Toyota",
        "5YJ" to "Tesla", "7SA" to "Tesla", "7G2" to "Tesla",
        "WBA" to "BMW", "WBS" to "BMW M", "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz",
        "WAU" to "Audi", "WVW" to "Volkswagen", "WP0" to "Porsche",
        "KMH" to "Hyundai", "5NP" to "Hyundai", "KNA" to "Kia", "5XY" to "Kia",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "VF1" to "Renault", "VF3" to "Peugeot",
        "ZFF" to "Ferrari", "ZAR" to "Alfa Romeo", "YV1" to "Volvo",
    )

    fun decode(raw: String): VinResult {
        val vin = raw.trim().uppercase().replace(" ", "").replace("-", "")

        if (vin.length != 17) return VinResult.Failure("VIN must be 17 characters (got ${vin.length}).")
        if (!VIN_REGEX.matches(vin)) {
            val bad = vin.filter { it !in "ABCDEFGHJKLMNPRSTUVWXYZ0123456789" }
            val hint = if (bad.isEmpty()) {
                "contains the letters I, O or Q, which are not allowed"
            } else {
                "contains invalid characters: $bad"
            }
            return VinResult.Failure("VIN $hint.")
        }

        val wmi = vin.substring(0, 3)
        val region = regionOf(vin[0])
        val checkDigit = vin[8]
        val candidates = modelYearCandidates(vin[9])

        val info = VinInfo(
            vin = vin,
            wmi = wmi,
            vds = vin.substring(3, 9),
            vis = vin.substring(9),
            region = region,
            country = countryOf(vin[0], vin[1]),
            manufacturer = WMI_TABLE[wmi],
            modelYearCandidates = candidates,
            modelYear = resolveModelYear(vin, candidates),
            plantCode = vin[10],
            serialNumber = vin.substring(11),
            checkDigit = checkDigit,
            checkDigitValid = computeCheckDigit(vin) == checkDigit,
            isSmallManufacturer = vin[2] == '9',
        )
        return VinResult.Success(info)
    }

    /** ISO 3779 / 49 CFR 565 check digit: weighted sum of transliterated chars mod 11 ('X' = 10). */
    fun computeCheckDigit(vin: String): Char {
        var sum = 0
        for (i in 0 until 17) {
            sum += (TRANSLITERATION[vin[i]] ?: return '?') * WEIGHTS[i]
        }
        val r = sum % 11
        return if (r == 10) 'X' else ('0' + r)
    }

    private fun regionOf(c: Char): String = when (c) {
        in 'A'..'H' -> "Africa"
        in 'J'..'R' -> "Asia"
        in 'S'..'Z' -> "Europe"
        in '1'..'5' -> "North America"
        '6', '7' -> "Oceania"
        '8', '9' -> "South America"
        else -> "Unknown"
    }

    /** Coarse country lookup (first one or two characters). Not exhaustive. */
    private fun countryOf(c1: Char, c2: Char): String? = when (c1) {
        '1', '4', '5' -> "United States"
        '2' -> "Canada"
        '3' -> "Mexico"
        '6' -> "Australia"
        '9' -> if (c2 in 'A'..'E' || c2 in '3'..'9') "Brazil" else null
        'J' -> "Japan"
        'K' -> "South Korea"
        'L' -> "China"
        'M' -> if (c2 in 'A'..'E') "India" else null
        'S' -> if (c2 in 'A'..'M') "United Kingdom" else if (c2 in 'N'..'T') "Germany" else null
        'V' -> if (c2 in 'F'..'R') "France" else if (c2 in 'S'..'W') "Spain" else null
        'W' -> "Germany"
        'Y' -> if (c2 in 'A'..'E') "Belgium" else if (c2 in 'S'..'W') "Sweden" else null
        'Z' -> if (c2 in 'A'..'R') "Italy" else null
        else -> null
    }

    /** Position 10 maps to a year in either the 1980-2009 or 2010-2039 cycle. */
    private fun modelYearCandidates(code: Char): List<Int> {
        val idx = YEAR_CODES.indexOf(code)
        return if (idx < 0) emptyList() else listOf(1980 + idx, 2010 + idx)
    }

    /**
     * North American rule: if position 7 is a letter, the year is in the 2010-2039 cycle;
     * if it's a digit, 1980-2009. (Applies to passenger cars and light trucks.)
     * For other regions this heuristic isn't reliable, so we only resolve for North America
     * and otherwise pick the latest candidate that isn't in the far future.
     */
    private fun resolveModelYear(vin: String, candidates: List<Int>): Int? {
        if (candidates.isEmpty()) return null
        val nextYear = java.time.Year.now().value + 1
        if (vin[0] in '1'..'5') {
            return if (vin[6].isLetter()) candidates[1] else candidates[0]
        }
        return candidates.filter { it <= nextYear }.maxOrNull()
    }
}
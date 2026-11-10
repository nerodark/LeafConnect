package leafconnect

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Region and language codes for the authenticate body, plus the unit rules the
 * selected region implies.
 *
 * The region derivation mirrors the official app's mapping onto the two
 * regions the backend knows: `CA` for Canada, `US` for everything else. The
 * chosen region is stored at login ([PREF_COUNTRY]), so a session keeps
 * authenticating with the region it was created in even if the device locale
 * changes later — the region of an account does not follow the phone.
 *
 * The country/language rules are pure functions of a code string; the device
 * codes come from [deviceCountryCode] / [deviceLanguageCode], which keeps every
 * rule testable in common code.
 */
object AppLocale {

    /** Settings key holding the region code the current session authenticates with. */
    const val PREF_COUNTRY = "country"

    const val COUNTRY_CA = "CA"
    const val COUNTRY_US = "US"
    const val LANGUAGE_EN = "EN"
    const val LANGUAGE_FR = "FR"

    const val KM_TO_MILES = 0.621371
    const val FAHRENHEIT_FACTOR = 1.8
    const val FAHRENHEIT_OFFSET = 32.0

    /** `EN` / `FR`; the backend accepts only these two, anything else is English. */
    fun languageOf(languageCode: String): String = when {
        languageCode.equals("en", ignoreCase = true) -> LANGUAGE_EN
        languageCode.equals("fr", ignoreCase = true) -> LANGUAGE_FR
        else -> LANGUAGE_EN
    }

    /** `CA` for Canada, `US` for every other country. */
    fun countryOf(countryCode: String): String =
        if (countryCode.equals(COUNTRY_CA, ignoreCase = true)) COUNTRY_CA else COUNTRY_US

    fun languageCode(): String = languageOf(deviceLanguageCode())

    fun countryCode(): String = countryOf(deviceCountryCode())

    /**
     * The region to authenticate with: the value stored at login (which the
     * login screen's selector writes), falling back to the device locale when
     * nothing is stored yet — so the selector always shows exactly the region a
     * sign-in would use.
     */
    fun sessionCountry(store: SettingsStore): String =
        store.getString(PREF_COUNTRY)?.takeIf { it.isNotEmpty() } ?: countryCode()

    /**
     * True when the session shows distances in miles. The payload reports
     * kilometres (observed for a CA account), so a US session converts the
     * values rather than relabeling them — the official client ships both a
     * KILOMETRE RANGE and a MILE RANGE label.
     */
    fun usesMiles(store: SettingsStore): Boolean = sessionCountry(store) == COUNTRY_US

    /** True when the session shows Fahrenheit; `inc_temp` is Celsius in the payload. */
    fun usesFahrenheit(store: SettingsStore): Boolean = sessionCountry(store) == COUNTRY_US

    /** Kilometres to miles; round with `roundToInt()` — the payload is a whole-number range. */
    fun milesFromKm(km: Double): Double = km * KM_TO_MILES

    /** Celsius to Fahrenheit. */
    fun fahrenheitFromC(celsius: Double): Double = celsius * FAHRENHEIT_FACTOR + FAHRENHEIT_OFFSET

    /**
     * One decimal place, locale-independent: `48.2`, `0.5`, `-4.5`. Avoids
     * platform formatting, whose decimal separator follows the device locale.
     */
    fun formatOneDecimal(value: Double): String {
        val tenths = (value * 10).roundToInt()
        val whole = tenths / 10
        return "$whole.${abs(tenths - whole * 10)}"
    }
}

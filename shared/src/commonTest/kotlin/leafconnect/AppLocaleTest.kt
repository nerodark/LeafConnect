package leafconnect

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The region/language rules the app authenticates with (the official app's
 * mapping): Canada keeps `CA`, every other country becomes `US`, and only
 * English/French languages map to a code — anything else falls back to English.
 *
 * In the multiplatform app the device codes come from a platform seam, so the
 * rules are tested as pure functions of the code strings.
 */
class AppLocaleTest {

    @Test
    fun canadianLocalesKeepCanada() {
        assertEquals(AppLocale.COUNTRY_CA, AppLocale.countryOf("CA"))
        assertEquals(AppLocale.LANGUAGE_EN, AppLocale.languageOf("en"))
        // French Canada: both spellings of the language count.
        assertEquals(AppLocale.LANGUAGE_FR, AppLocale.languageOf("fr"))
        assertEquals(AppLocale.LANGUAGE_FR, AppLocale.languageOf("FR"))
    }

    @Test
    fun anyOtherCountryBecomesUnitedStates() {
        assertEquals(AppLocale.COUNTRY_US, AppLocale.countryOf("US"))
        // The backend knows CA and US only — everything else maps onto US
        // rather than sending an unknown code.
        assertEquals(AppLocale.COUNTRY_US, AppLocale.countryOf("FR"))
        assertEquals(AppLocale.COUNTRY_US, AppLocale.countryOf("DE"))
    }

    @Test
    fun localeWithoutCountryIsNotCanada() {
        // A bare language locale reports an empty country, so it must not be
        // read as Canada (it falls back to United States).
        assertEquals(AppLocale.COUNTRY_US, AppLocale.countryOf(""))
    }

    @Test
    fun unsupportedLanguagesFallBackToEnglish() {
        assertEquals(AppLocale.LANGUAGE_EN, AppLocale.languageOf("de"))
        assertEquals(AppLocale.COUNTRY_CA, AppLocale.countryOf("CA"))
    }

    @Test
    fun storedRegionWinsOverTheDeviceOne() {
        val store = FakeSettingsStore()
        // Nothing stored yet: the device locale decides, through the seam.
        assertEquals(AppLocale.countryOf(deviceCountryCode()), AppLocale.sessionCountry(store))
        store.put(AppLocale.PREF_COUNTRY, AppLocale.COUNTRY_CA)
        assertEquals(AppLocale.COUNTRY_CA, AppLocale.sessionCountry(store))
        store.put(AppLocale.PREF_COUNTRY, AppLocale.COUNTRY_US)
        assertEquals(AppLocale.COUNTRY_US, AppLocale.sessionCountry(store))
        // A US session switches both distance and temperature units.
        assertEquals(true, AppLocale.usesMiles(store))
        assertEquals(true, AppLocale.usesFahrenheit(store))
        store.put(AppLocale.PREF_COUNTRY, AppLocale.COUNTRY_CA)
        assertEquals(false, AppLocale.usesMiles(store))
        assertEquals(false, AppLocale.usesFahrenheit(store))
    }

    @Test
    fun kilometreRangesConvertToMiles() {
        // The payload is kilometre-based; a United States session converts it.
        // These are the values a CA account reports at ~70% state of charge.
        assertEquals(151, AppLocale.milesFromKm(243.0).roundToInt())
        assertEquals(160, AppLocale.milesFromKm(257.0).roundToInt())
        assertEquals(0, AppLocale.milesFromKm(0.0).roundToInt())
    }

    @Test
    fun celsiusTempsConvertToFahrenheit() {
        // inc_temp is Celsius; these are readings a CA account reported.
        assertEquals("48.2", AppLocale.formatOneDecimal(AppLocale.fahrenheitFromC(9.0)))
        assertEquals("61.7", AppLocale.formatOneDecimal(AppLocale.fahrenheitFromC(16.5)))
        assertEquals("32.0", AppLocale.formatOneDecimal(AppLocale.fahrenheitFromC(0.0)))
    }

    @Test
    fun oneDecimalFormattingIsLocaleIndependent() {
        assertEquals("0.5", AppLocale.formatOneDecimal(0.5))
        assertEquals("-4.5", AppLocale.formatOneDecimal(-4.5))
        assertEquals("-40.0", AppLocale.formatOneDecimal(-40.0))
        assertEquals("212.0", AppLocale.formatOneDecimal(212.0))
    }
}

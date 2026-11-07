package leafconnect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The region/language derivation the app now authenticates with (the official
 * app's `Utils.getLocale()` mapping): Canada keeps `CA`, every other locale
 * becomes `United States`, and only English/French languages map to a code —
 * anything else falls back to English.
 *
 * `Locale.setDefault()` is process-wide, so each test restores it.
 */
class AppLocaleTest {

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun canadianLocalesKeepCanada() {
        withLocale(Locale("en", "CA")) {
            assertEquals(AppLocale.COUNTRY_CA, AppLocale.countryCode())
            assertEquals(AppLocale.LANGUAGE_EN, AppLocale.languageCode())
        }
        // French Canada: displayLanguage is localized, so both spellings count.
        withLocale(Locale("fr", "CA")) {
            assertEquals(AppLocale.COUNTRY_CA, AppLocale.countryCode())
            assertEquals(AppLocale.LANGUAGE_FR, AppLocale.languageCode())
        }
    }

    @Test
    fun anyOtherCountryBecomesUnitedStates() {
        withLocale(Locale("en", "US")) {
            assertEquals(AppLocale.COUNTRY_US, AppLocale.countryCode())
            assertEquals(AppLocale.LANGUAGE_EN, AppLocale.languageCode())
        }
        // The backend knows CA and US only — the official bootstrap maps
        // everything else onto United States rather than sending an unknown code.
        withLocale(Locale("fr", "FR")) {
            assertEquals(AppLocale.COUNTRY_US, AppLocale.countryCode())
        }
        withLocale(Locale("de", "DE")) {
            assertEquals(AppLocale.COUNTRY_US, AppLocale.countryCode())
        }
    }

    @Test
    fun localeWithoutCountryIsNotCanada() {
        // A bare language locale reports an empty country, so it must not be
        // read as Canada (it falls back to United States).
        withLocale(Locale.ENGLISH) {
            assertEquals(AppLocale.COUNTRY_US, AppLocale.countryCode())
        }
    }

    @Test
    fun unsupportedLanguagesFallBackToEnglish() {
        withLocale(Locale("de", "CA")) {
            assertEquals(AppLocale.LANGUAGE_EN, AppLocale.languageCode())
            assertEquals(AppLocale.COUNTRY_CA, AppLocale.countryCode())
        }
    }
}

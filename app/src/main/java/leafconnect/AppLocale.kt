package leafconnect

import java.util.Locale

/**
 * Region and language codes for the authenticate body, derived the way the
 * official app derives them (`Utils.getLocale()` plus its first-launch
 * bootstrap in `SplashScreenActivity.checkTermsVersion`).
 *
 * The official build ships a Canada-only login, but its bootstrap maps the
 * device locale onto the two regions the backend knows: `CA` for Canada and
 * `United States` for everything else. Deriving instead of hardcoding is what
 * lets this app sign in from a US account as well.
 *
 * The derived region is stored in [PREF_COUNTRY] at login, so a session keeps
 * authenticating with the region it was created in even if the device locale
 * changes later (the region of an account does not follow the phone).
 */
object AppLocale {

    /** Prefs key holding the region code the current session authenticates with. */
    const val PREF_COUNTRY = "country"

    /**
     * `EN` / `FR` from the locale's display language, exactly as
     * `Utils.getLocale()[0]`: English and French map to their codes, anything
     * else falls back to English (the backend only accepts these two).
     */
    fun languageCode(): String {
        val displayLanguage = Locale.getDefault().displayLanguage
        return when {
            displayLanguage.equals("English", ignoreCase = true) -> LANGUAGE_EN
            displayLanguage.equals("français", ignoreCase = true) ||
                displayLanguage.equals("french", ignoreCase = true) -> LANGUAGE_FR
            else -> LANGUAGE_EN
        }
    }

    /**
     * Region code of the device locale: `CA` for Canada, `US` otherwise — the
     * CA/US mapping the official bootstrap applies (`Locale.getDefault()
     * .getCountry()` normalized the way `Utils.getLocale()[1]` normalizes it).
     */
    fun countryCode(): String =
        if (Locale.getDefault().country.equals(COUNTRY_CA, ignoreCase = true)) COUNTRY_CA else COUNTRY_US

    /**
     * The region to authenticate with: the value stored at login (which the
     * login screen's selector writes), falling back to the device locale when
     * nothing is stored yet — so the selector always shows exactly the region a
     * sign-in would use.
     */
    fun sessionCountry(prefs: SecurePreferences): String =
        prefs.getString(PREF_COUNTRY)?.takeIf { it.isNotEmpty() } ?: countryCode()

    const val COUNTRY_CA = "CA"
    const val COUNTRY_US = "US"
    const val LANGUAGE_EN = "EN"
    const val LANGUAGE_FR = "FR"
}

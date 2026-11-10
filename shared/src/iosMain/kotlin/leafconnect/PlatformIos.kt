package leafconnect

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS platform seams.
 *
 * NSURLSession (Ktor's Darwin engine) retries failed connections on its own,
 * matching Android's OkHttp behavior. Timeouts are configured once, in the
 * shared HTTP client.
 */
actual fun httpEngine(): HttpClientEngine = Darwin.create()

/** The first preferred language, e.g. "en-CA" → "en". */
actual fun deviceLanguageCode(): String =
    NSLocale.preferredLanguages.firstOrNull()?.toString()?.substringBefore("-") ?: "en"

/**
 * The device country, without the `countryCode` API Apple deprecated on iOS 26:
 * `localeIdentifier` carries it, e.g. "en_CA" → "CA". The authenticate body
 * accepts CA and US only, and `AppLocale.countryOf` maps anything else to US.
 */
actual fun deviceCountryCode(): String =
    NSLocale.currentLocale.localeIdentifier.split("_").getOrNull(1) ?: "US"

/**
 * Open Apple Maps at the coordinates (q= names the point, ll= centers it).
 * Returns false when nothing can open the URL, so the UI reports it instead of
 * failing silently.
 */
actual fun openMaps(latitude: String, longitude: String): Boolean {
    val url = NSURL.URLFromString("https://maps.apple.com/?ll=$latitude,$longitude&q=$latitude,$longitude")
        ?: return false
    val application = UIApplication.sharedApplication ?: return false
    if (!application.canOpenURL(url)) return false
    application.openURL(url)
    return true
}

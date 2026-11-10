package leafconnect

import io.ktor.client.engine.HttpClientEngine

/**
 * Platform seams. Everything else in the app is shared Kotlin: the HTTP stack,
 * the session logic and the Compose UI are platform-neutral, and only these
 * four facts differ per platform.
 */

/**
 * Ktor engine for the current platform: OkHttp on Android (its
 * `retryOnConnectionFailure` absorbs the "Software caused connection abort"
 * keep-alive failures), NSURLSession on iOS (same automatic retry). Timeouts
 * are configured inside each actual — the battery endpoint takes 16-60+ s.
 */
expect fun httpEngine(): HttpClientEngine

/** ISO-639 language code of the device locale (`en`, `fr`, …). */
expect fun deviceLanguageCode(): String

/** ISO-3166 country code of the device locale (`CA`, `US`, …). */
expect fun deviceCountryCode(): String

/**
 * Open a map at the given coordinates. Returns false when no map handler is
 * available, so the UI can say so instead of throwing.
 */
expect fun openMaps(latitude: String, longitude: String): Boolean

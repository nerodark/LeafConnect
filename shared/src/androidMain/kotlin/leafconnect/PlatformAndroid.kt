package leafconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.Locale

/**
 * Android platform seams.
 *
 * The OkHttp engine keeps its default `retryOnConnectionFailure`, which absorbs
 * the keep-alive abort failures the old hand-rolled retry covered. Timeouts are
 * configured once, in the shared HTTP client.
 */
actual fun httpEngine(): HttpClientEngine = OkHttp.create()

actual fun deviceLanguageCode(): String = Locale.getDefault().language

actual fun deviceCountryCode(): String = Locale.getDefault().country

/**
 * Open a map at the coordinates: Google Maps when installed (the package
 * restriction keeps the chooser from appearing), then the web maps URL as the
 * fallback. Returns false when neither can start — no maps app at all.
 */
actual fun openMaps(latitude: String, longitude: String): Boolean {
    val context = AndroidAppContext.require()

    val geo = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
    if (startView(context, Intent(Intent.ACTION_VIEW, geo).apply { setPackage("com.google.android.apps.maps") })) {
        return true
    }
    return startView(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$latitude,$longitude")))
}

private fun startView(context: Context, intent: Intent): Boolean = try {
    // The application context needs its own task to start an activity.
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: Exception) {
    false
}

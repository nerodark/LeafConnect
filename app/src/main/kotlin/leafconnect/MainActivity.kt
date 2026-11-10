package leafconnect

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * The Android entry point: window chrome (framework splash screen, edge-to-edge)
 * around the shared Compose UI. Login and home are screens inside `App()`, not
 * activities — this module contributes only platform wiring.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The framework splash outlasts the activity's own 1 s overlay in App().
        installSplashScreen()
        // The settings store (Keystore + Tink) needs the application context;
        // install it before anything reads prefs.
        AndroidAppContext.install(applicationContext)
        enableEdgeToEdge()
        setContent { App() }
    }
}

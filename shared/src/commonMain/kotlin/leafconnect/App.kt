package leafconnect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import leafconnect.generated.resources.Res
import leafconnect.generated.resources.account_id_missing
import leafconnect.generated.resources.app_name
import leafconnect.generated.resources.canceled
import leafconnect.generated.resources.charging_start_failed
import leafconnect.generated.resources.charging_started
import leafconnect.generated.resources.connection_error
import leafconnect.generated.resources.connection_slow
import leafconnect.generated.resources.email_invalid
import leafconnect.generated.resources.email_required
import leafconnect.generated.resources.ic_launcher
import leafconnect.generated.resources.ic_launcher_dark
import leafconnect.generated.resources.location_not_available
import leafconnect.generated.resources.lock_command_sent
import leafconnect.generated.resources.lock_failed
import leafconnect.generated.resources.login_failed
import leafconnect.generated.resources.no_maps_app
import leafconnect.generated.resources.no_vehicles
import leafconnect.generated.resources.password_required
import leafconnect.generated.resources.still_loading
import leafconnect.generated.resources.temperature_control_start_failed
import leafconnect.generated.resources.temperature_control_started
import leafconnect.generated.resources.temperature_control_stop_failed
import leafconnect.generated.resources.temperature_control_stopped
import leafconnect.generated.resources.unlock_command_sent
import leafconnect.generated.resources.unlock_failed
import org.jetbrains.compose.resources.stringResource

/**
 * The whole app in shared Compose UI: login screen, home screen, the transient
 * message line (Snackbar — the cross-platform toast), and the launch splash.
 * The host (`app/src/main/...` on Android, `iosMain/MainViewController.kt` on
 * iOS) wraps this in its window chrome.
 */
@Composable
fun App(controller: AppController = remember { AppController(MainScope()) }) {
    val screen by controller.screen.collectAsState()
    val toast by controller.toast.collectAsState()
    val splash by controller.splash.collectAsState()
    val loading by controller.loading.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { controller.start() }

    // The message text is resolved during composition (stringResource is a
    // composable call); the effect only shows and clears it.
    val toastMessage = toast?.let { toastText(it) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            snackbar.showSnackbar(toastMessage, duration = SnackbarDuration.Long)
            controller.toast.value = null
        }
    }

    LeafConnectTheme {
        Box(
            // Keep content clear of system bars/cutouts on every platform.
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        ) {
            when (screen) {
                Screen.LOGIN -> LoginScreen(controller)
                Screen.HOME -> HomeScreen(controller)
            }

            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))

            // The login round covers the form with the dim + spinner, exactly
            // like overlay_login did; Home has its own overlay with CANCEL.
            if (loading && screen == Screen.LOGIN) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0x80000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            AnimatedVisibility(visible = splash) {
                Box(
                    modifier = Modifier.fillMaxSize().background(LocalLeafPalette.current.windowBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = if (isSystemInDarkTheme()) {
                            painterResource(Res.drawable.ic_launcher_dark)
                        } else {
                            painterResource(Res.drawable.ic_launcher)
                        },
                        contentDescription = stringResource(Res.string.app_name),
                        modifier = Modifier.size(288.dp),
                    )
                }
            }
        }
    }
}

/** Message keys resolve against the shared string resources at render time. */
@Composable
private fun toastText(toast: Toast): String {
    val argument = toast.argument ?: ""
    return when (toast.key) {
        ToastKey.CANCELED -> stringResource(Res.string.canceled)
        ToastKey.CONNECTION_SLOW -> stringResource(Res.string.connection_slow)
        ToastKey.STILL_LOADING -> stringResource(Res.string.still_loading)
        ToastKey.LOGIN_FAILED -> stringResource(Res.string.login_failed)
        ToastKey.EMAIL_REQUIRED -> stringResource(Res.string.email_required)
        ToastKey.EMAIL_INVALID -> stringResource(Res.string.email_invalid)
        ToastKey.PASSWORD_REQUIRED -> stringResource(Res.string.password_required)
        ToastKey.CONNECTION_ERROR -> stringResource(Res.string.connection_error, argument)
        ToastKey.NO_MAPS_APP -> stringResource(Res.string.no_maps_app)
        ToastKey.ACCOUNT_ID_MISSING -> stringResource(Res.string.account_id_missing)
        ToastKey.LOCATION_NOT_AVAILABLE -> stringResource(Res.string.location_not_available)
        ToastKey.NO_VEHICLES -> stringResource(Res.string.no_vehicles)
        ToastKey.TEMP_STARTED -> stringResource(Res.string.temperature_control_started)
        ToastKey.TEMP_START_FAILED -> stringResource(Res.string.temperature_control_start_failed)
        ToastKey.TEMP_STOPPED -> stringResource(Res.string.temperature_control_stopped)
        ToastKey.TEMP_STOP_FAILED -> stringResource(Res.string.temperature_control_stop_failed)
        ToastKey.CHARGE_STARTED -> stringResource(Res.string.charging_started)
        ToastKey.CHARGE_START_FAILED -> stringResource(Res.string.charging_start_failed)
        ToastKey.LOCK_SENT -> stringResource(Res.string.lock_command_sent)
        ToastKey.LOCK_FAILED -> stringResource(Res.string.lock_failed)
        ToastKey.UNLOCK_SENT -> stringResource(Res.string.unlock_command_sent)
        ToastKey.UNLOCK_FAILED -> stringResource(Res.string.unlock_failed)
        ToastKey.RAW_MESSAGE -> argument
    }
}

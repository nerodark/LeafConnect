package leafconnect

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The iOS entry point: the shared Compose UI inside a UIKit view controller.
 * Wire it up in the iOS host app (SceneDelegate or AppDelegate window):
 *
 * ```swift
 * window.rootViewController = MainViewController()
 * ```
 *
 * The iOS targets compile and link only on macOS (Kotlin/Native needs Xcode);
 * this file is declared so the shared UI has a real entry point on that
 * platform.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController { App() }

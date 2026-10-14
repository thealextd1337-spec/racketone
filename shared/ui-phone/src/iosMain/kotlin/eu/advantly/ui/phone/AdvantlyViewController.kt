package eu.advantly.ui.phone

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/** Host-ViewController für die iOS-App; wird über `umbrella-ios` (AdvantlyKit) an Swift gereicht. */
fun advantlyViewController(): UIViewController {
    val versionName =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"
    return ComposeUIViewController { AdvantlyApp(versionName = versionName) }
}

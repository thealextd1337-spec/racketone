package eu.advantly.umbrella.ios

import eu.advantly.ui.phone.advantlyViewController
import platform.UIKit.UIViewController

/**
 * Öffentliche Swift-API von `AdvantlyKit` – bewusst klein halten (Facade).
 * Swift: `AdvantlyKitKt.mainViewController()`.
 */
fun mainViewController(): UIViewController = advantlyViewController()

package eu.advantly.match.session

import eu.advantly.sync.protocol.AppBuild
import platform.Foundation.NSBundle
import kotlin.time.Instant

/** Dieselben Info.plist-Werte wie Swift; Release ohne Bauzeit. */
fun appleAppBuild(): AppBuild {
    fun value(key: String): String = NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: ""
    val time = value("AdvantlyBuildTime").toLongOrNull()?.let { Instant.fromEpochSeconds(it).toString() }.orEmpty()
    return AppBuild(value("CFBundleShortVersionString"), value("AdvantlyGitCommit"), time)
}

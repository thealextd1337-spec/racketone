package eu.advantly.match.session

import android.content.Context
import eu.advantly.sync.protocol.AppBuild

/** App-eigene generierte Ressourcen; Session-Modul kennt keinen App-Namespace. */
@Suppress("DiscouragedApi", "DEPRECATION")
internal fun androidAppBuild(context: Context): AppBuild {
    fun resource(name: String): String =
        context.resources
            .getIdentifier(name, "string", context.packageName)
            .takeIf { it != 0 }
            ?.let(context::getString)
            .orEmpty()
    val version =
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
    return AppBuild(version, resource("build_git_commit"), resource("build_time_utc"))
}

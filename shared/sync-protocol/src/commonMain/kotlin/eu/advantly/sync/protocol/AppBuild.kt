package eu.advantly.sync.protocol

import kotlinx.serialization.Serializable

/** Optionaler App-Stand im Hello (F31); fehlend bleibt unbekannt, Protokoll 3 unverändert. */
@Serializable
data class AppBuild(
    val version: String,
    val commit: String,
    val timeUtc: String = "",
)

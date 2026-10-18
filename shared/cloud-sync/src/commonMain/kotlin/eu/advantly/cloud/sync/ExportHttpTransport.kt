package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/** Ein HTTP-Aufruf besitzt diesen nackten Client und dessen native Abschlussbarriere exklusiv. */
internal interface ExportHttpTransport {
    val client: HttpClient

    fun cancel()

    /** Erst nach dem Ktor-Request-Join aufrufen; es darf danach kein nativer Call mehr starten. */
    suspend fun awaitCompletion()

    fun close()
}

/** Interner Vertrauensvertrag; injizierte Engines brauchen einen eigenen belegten Drain. */
internal fun interface ExportHttpTransportFactory {
    fun open(): ExportHttpTransport
}

internal expect fun platformExportHttpTransport(): ExportHttpTransport

internal fun exportHttpTransportFactory(injected: HttpClientEngine?): ExportHttpTransportFactory =
    if (injected == null) {
        ExportHttpTransportFactory { platformExportHttpTransport() }
    } else {
        injected as? ExportHttpTransportFactory ?: ExportHttpTransportFactory { throw CloudException("export_unavailable", false) }
    }

package eu.advantly.cloud.sync

/** ZIP-Export bleibt ohne separat nachgewiesene native Transportbarriere gesperrt; Auth/RPC bleiben erhalten. */
internal actual fun platformExportHttpTransport(): ExportHttpTransport = throw CloudException("export_unavailable", false)

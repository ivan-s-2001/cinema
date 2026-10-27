package com.nuvio.tv.data.repository

import java.net.URI

/**
 * One catalogue authority for CinemaHall.
 * The legacy add-on transport is kept for compatibility with Nuvio's UI,
 * but arbitrary remote manifests/catalogues/streams are never queried.
 */
internal object CinemaHallSourcePolicy {
    private const val CATALOG_ID = "app.cinemahall.catalog"
    private const val LOCAL_PORT = 18991

    fun isOwnCatalog(baseUrl: String, addonId: String): Boolean {
        if (addonId != CATALOG_ID) return false
        return try {
            val uri = URI(baseUrl)
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host == "127.0.0.1" &&
                uri.port == LOCAL_PORT &&
                (uri.rawPath.isNullOrBlank() || uri.rawPath == "/") &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        } catch (_: Exception) {
            false
        }
    }
}

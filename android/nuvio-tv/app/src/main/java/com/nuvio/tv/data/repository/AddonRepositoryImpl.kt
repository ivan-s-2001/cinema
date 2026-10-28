package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** The add-on protocol is only a local adapter to the CinemaHall server. */
@Singleton
class AddonRepositoryImpl(
    private val api: AddonApi,
    private val context: Context,
    dispatcher: CoroutineDispatcher
) : AddonRepository {
    @Inject
    constructor(api: AddonApi, @ApplicationContext context: Context) : this(api, context, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val endpoint = "http://127.0.0.1:18991"

    // No empty initial emission: playback, search and discover all use first().
    // The single successful manifest is replayed to every screen, without disk
    // caches, profile observers, external manifests or background refresh sweeps.
    private val provider = flow {
        var attempts = 0
        while (true) {
            val result = fetchAddon(endpoint)
            if (result is NetworkResult.Success) {
                emit(listOf(result.data.copy(enabled = true)))
                break
            }
            attempts++
            delay(if (attempts < 5) attempts * 500L else 5_000L)
        }
    }.flowOn(dispatcher).shareIn(scope, SharingStarted.Lazily, replay = 1)

    override fun getInstalledAddons(): Flow<List<Addon>> = provider

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        val url = baseUrl.trim().trimEnd('/').removeSuffix("/manifest.json")
        if (url != endpoint) return NetworkResult.Error("Доступен только каталог CinemaHall")
        return when (val result = safeApiCall(context) { api.getManifest("$endpoint/manifest.json") }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(endpoint)
                if (CinemaHallSourcePolicy.isOwnCatalog(addon.baseUrl, addon.id)) {
                    NetworkResult.Success(addon.copy(enabled = true))
                } else NetworkResult.Error("Не удалось загрузить каталог CinemaHall")
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // Kept as inert interface adapters for settings screens. The built-in
    // provider cannot be disabled, reordered, removed or replaced externally.
    override suspend fun addAddon(url: String) = Unit
    override suspend fun removeAddon(url: String) = Unit
    override suspend fun setAddonOrder(urls: List<String>) = Unit
    override suspend fun setAddonEnabled(url: String, enabled: Boolean) = Unit
    var isSyncingFromRemote = false
    suspend fun reconcileWithRemoteAddonUrls(remoteUrls: List<String>, removeMissingLocal: Boolean = true) = Unit
}

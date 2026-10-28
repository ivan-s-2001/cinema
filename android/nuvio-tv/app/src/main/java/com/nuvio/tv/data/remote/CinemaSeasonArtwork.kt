package com.nuvio.tv.data.remote

import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Fetch only the selected season's server-prepared metadata, with no provider work on TV. */
internal object CinemaSeasonArtwork {
    data class Artwork(val title: String?, val still: String?, val overview: String?)
    suspend fun load(titleId: String, season: Int): Map<String, Artwork> = withContext(Dispatchers.IO) {
        if (!titleId.matches(Regex("ch:[A-Za-z0-9:_-]{1,157}")) || season !in 1..999) return@withContext emptyMap()
        val result = linkedMapOf<String, Artwork>(); var after = 0
        do {
            val c = URL("https://cinemahome.duckdns.org/api/v2/seasons/$titleId:s$season/episodes?limit=100&after=$after").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 25000; c.instanceFollowRedirects = false
            val data = try {
                check(c.responseCode == 200) { "Не удалось загрузить кадры сезона" }
                c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size() + n <= 512 * 1024); out.write(buffer, 0, n) }
                    JSONObject(out.toString("UTF-8"))
                }
            } finally { c.disconnect() }
            val rows = data.getJSONArray("items")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                fun field(key: String) = if (row.isNull(key)) null else row.optString(key).takeIf { it.isNotBlank() }
                result[row.getString("id")] = Artwork(field("title"), field("still"), field("description"))
            }
            val next = if (data.isNull("nextAfter")) 0 else data.optInt("nextAfter")
            if (next <= after) break
            after = next
        } while (result.size < 2000)
        result
    }
    fun merge(video: Video, images: Map<String, Artwork>): Video = images[video.id]?.let { art ->
        video.copy(title = art.title ?: video.title, thumbnail = art.still ?: video.thumbnail, overview = art.overview ?: video.overview)
    } ?: video
}

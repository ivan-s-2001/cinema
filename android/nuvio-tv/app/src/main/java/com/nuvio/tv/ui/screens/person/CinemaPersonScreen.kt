package com.nuvio.tv.ui.screens.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class Film(val id: String, val type: String, val title: String, val poster: String, val year: String)
internal data class Filmography(val name: String, val photo: String, val items: List<Film>, val cursor: String?)

private suspend fun loadPerson(id: String, cursor: String?): Filmography = withContext(Dispatchers.IO) {
    require(id.matches(Regex("person:[a-f0-9]{32}")))
    val suffix = cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
    val connection = URL("https://cinemahome.duckdns.org/api/v2/people/$id/filmography?limit=24&sort=year$suffix").openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 15000
    connection.instanceFollowRedirects = false
    try {
        check(connection.responseCode == 200) { "Фильмография временно недоступна" }
        val text = connection.inputStream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) { val size = input.read(chunk); if (size < 0) break; check(buffer.size() + size <= 512 * 1024); buffer.write(chunk, 0, size) }
            buffer.toString("UTF-8")
        }
        val response = JSONObject(text)
        val person = response.getJSONObject("person")
        val rows = response.getJSONArray("items")
        Filmography(person.getString("name"), person.optString("photo"), (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Film(row.getString("id"), row.getString("type"), row.getString("title"), row.optString("poster"), if (row.isNull("year")) "" else row.optString("year"))
        }, if (response.isNull("nextCursor")) null else response.getString("nextCursor"))
    } finally { connection.disconnect() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CinemaPersonScreen(personId: String, onBack: () -> Unit, onOpenFilm: (String, String) -> Unit) {
    BackHandler(onBack = onBack)
    var data by remember(personId) { mutableStateOf<Filmography?>(null) }
    var cursor by remember(personId) { mutableStateOf<String?>(null) }
    var generation by remember(personId) { mutableIntStateOf(0) }
    var loading by remember(personId) { mutableStateOf(true) }
    var error by remember(personId) { mutableStateOf<String?>(null) }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(personId) { withFrameNanos { }; backFocus.requestFocus() }
    LaunchedEffect(personId, generation) {
        loading = true; error = null
        try {
            val page = loadPerson(personId, cursor)
            data = page.copy(items = (data?.items.orEmpty() + page.items).distinctBy { it.id })
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить фильмографию" }
        loading = false
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Button(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) { Text("Назад") }
            data?.let { person ->
                AsyncImage(model = person.photo, contentDescription = person.name, modifier = Modifier.size(64.dp, 88.dp), contentScale = ContentScale.Crop)
                Column {
                    Text(person.name, style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
                    Text("Фильмография · доступно в CinemaHall", color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        error?.let { message ->
            Text(message, color = NuvioTheme.colors.TextPrimary)
            Button(onClick = { generation++ }) { Text("Повторить") }
        }
        if (loading) Text("Загрузка…", color = NuvioTheme.colors.TextSecondary)
        if (!loading && data?.items?.isEmpty() == true) Text("Сейчас нет доступных произведений в каталоге", color = NuvioTheme.colors.TextPrimary)
        LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(data?.items.orEmpty(), key = { it.id }) { film ->
                Card(onClick = { onOpenFilm(film.id, film.type) }) {
                    Column {
                        AsyncImage(model = film.poster, contentDescription = film.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(180.dp))
                        Text(film.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp), color = NuvioTheme.colors.TextPrimary)
                        Text(film.year, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = NuvioTheme.colors.TextSecondary)
                    }
                }
            }
            if (!loading && data?.cursor != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = { cursor = data?.cursor; generation++ }) { Text("Ещё фильмы и сериалы") }
            }
        }
    }
}

package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private suspend fun catalogueJson(path: String): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL("https://cinemahome.duckdns.org/api/v2$path").openConnection() as HttpURLConnection
    connection.connectTimeout = 5000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
    try {
        check(connection.responseCode == 200) { "Каталог временно недоступен" }
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) { val n = input.read(chunk); if (n < 0) break; check(output.size() + n <= 512 * 1024); output.write(chunk, 0, n) }
            output.toString("UTF-8")
        }
        JSONObject(bytes)
    } finally { connection.disconnect() }
}

internal fun cinemaDiscoverPath(query: String, type: String, genre: String, year: String, country: String, sort: String, cursor: String?, shelf: String? = null): String {
    val scopedShelf = shelf?.takeIf { it.matches(Regex("[a-z_]+")) && query.trim().length < 2 }
    val args = linkedMapOf("limit" to "30")
    if (scopedShelf == null || sort != "popular") args["sort"] = sort
    if (query.trim().length >= 2) args["q"] = query.trim()
    if (type.isNotEmpty()) args["type"] = type
    if (genre.isNotEmpty()) args["genre"] = genre
    if (country.isNotEmpty()) args["country"] = country
    if (year.isNotEmpty()) { args["yearFrom"] = year; args["yearTo"] = year }
    if (cursor != null) args["cursor"] = cursor
    return (scopedShelf?.let { "/rows/$it/items?" } ?: "/discover?") + args.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
}
private fun JSONObject.options(key: String): List<String> = optJSONArray(key)?.let { values -> (0 until values.length()).map { values.getString(it) } }.orEmpty()
private data class DiscoveryFilm(val id: String, val type: String, val title: String, val poster: String, val subtitle: String)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CinemaDiscoverScreen(onNavigateToDetail: (String, String, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("popular") }
    var shelf by rememberSaveable { mutableStateOf("") }
    var shelves by remember { mutableStateOf(emptyList<Triple<String, String, String>>()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var facets by remember { mutableStateOf(JSONObject()) }
    var facetError by remember { mutableStateOf(false) }
    var facetRetry by remember { mutableIntStateOf(0) }
    var films by remember { mutableStateOf(emptyList<DiscoveryFilm>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val filterKey = cinemaDiscoverPath(query, type, genre, year, country, sort, null, shelf)
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) {
        try {
            val rows = catalogueJson("/config").getJSONArray("rows")
            shelves = (0 until rows.length()).map { rows.getJSONObject(it) }.map { Triple(it.getString("id"), it.getString("name"), it.getString("type")) }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { /* Global browsing remains available while shelves are offline. */ }
    }
    LaunchedEffect(type, facetRetry) {
        facetError = false
        try { facets = catalogueJson("/facets" + if (type.isEmpty()) "" else "?type=$type") }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { facetError = true }
    }
    LaunchedEffect(filterKey, page, retry) {
        loading = true; error = null
        val cursor = page
        if (cursor == null) { films = emptyList(); grid.scrollToItem(0) }
        try {
            delay(350)
            val response = catalogueJson(cinemaDiscoverPath(query, type, genre, year, country, sort, cursor, shelf))
            val rows = response.getJSONArray("items")
            val result = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val ratings = row.optJSONArray("ratings")
                val score = ratings?.let { scores -> (0 until scores.length()).map { scores.getJSONObject(it) }.firstOrNull { it.optDouble("scale") == 10.0 } }?.optDouble("value")
                DiscoveryFilm(row.getString("id"), row.getString("type"), row.getString("title"), row.optString("poster"),
                    listOfNotNull(if (row.isNull("year")) null else row.optString("year"), score?.let { "★ $it" }).joinToString(" · "))
            }
            films = if (cursor == null) result else (films + result).distinctBy { it.id }
            next = if (response.isNull("nextCursor")) null else response.getString("nextCursor")
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить каталог" }
        loading = false
    }
    fun changed() { page = null }
    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Каталог", style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
        Box(Modifier.fillMaxWidth().border(1.dp, NuvioTheme.colors.TextSecondary, RoundedCornerShape(8.dp)).padding(14.dp)) {
            if (query.isEmpty()) Text("Поиск по всей базе фильмов и сериалов", color = NuvioTheme.colors.TextSecondary)
            BasicTextField(value = query, onValueChange = { query = it.take(120); if (query.isNotEmpty()) shelf = ""; changed() }, singleLine = true,
                textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 18.sp), cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary), modifier = Modifier.fillMaxWidth())
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            item { Button(onClick = { dialog = "type" }) { Text(if (type == "movie") "Фильмы" else if (type == "series") "Сериалы" else "Все типы") } }
            if (shelves.isNotEmpty()) item { Button(onClick = { dialog = "shelf" }) { Text(shelves.find { it.first == shelf }?.second ?: "Подборки") } }
            item { Button(onClick = { dialog = "genre" }) { Text(genre.ifEmpty { "Все жанры" }) } }
            item { Button(onClick = { dialog = "year" }) { Text(year.ifEmpty { "Все годы" }) } }
            item { Button(onClick = { dialog = "country" }) { Text(country.ifEmpty { "Все страны" }) } }
            item { Button(onClick = { dialog = "sort" }) { Text(when (sort) { "year" -> "Сначала новые"; "rating" -> "По оценке"; "title" -> "По названию"; else -> "По популярности" }) } }
            item { Button(onClick = { type = ""; genre = ""; year = ""; country = ""; sort = "popular"; query = ""; shelf = ""; changed() }) { Text("Сбросить") } }
        }
        if (facetError) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Не удалось загрузить варианты фильтров", color = NuvioTheme.colors.TextSecondary)
            Button(onClick = { facetRetry++ }) { Text("Повторить") }
        }
        if (query.trim().length == 1) Text("Введите хотя бы две буквы", color = NuvioTheme.colors.TextSecondary)
        if (loading) Text("Загрузка…", color = NuvioTheme.colors.TextSecondary)
        error?.let { message -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = NuvioTheme.colors.TextPrimary)
            Button(onClick = { retry++ }) { Text("Повторить") }
        } }
        if (!loading && error == null && films.isEmpty()) Text("По этим условиям ничего не найдено", color = NuvioTheme.colors.TextPrimary)
        LazyVerticalGrid(columns = GridCells.Fixed(5), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(films, key = { it.id }) { film ->
                Card(onClick = { onNavigateToDetail(film.id, film.type, "http://127.0.0.1:18991") }) {
                    Column {
                        AsyncImage(model = film.poster, contentDescription = film.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        Text(film.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp), color = NuvioTheme.colors.TextPrimary)
                        Text(film.subtitle, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = NuvioTheme.colors.TextSecondary)
                    }
                }
            }
            if (!loading && next != null) item(span = { GridItemSpan(maxLineSpan) }) { Button(onClick = { page = next }) { Text("Показать ещё") } }
        }
    }
    dialog?.let { field ->
        val choices = when (field) {
            "type" -> listOf("" to "Все типы", "movie" to "Фильмы", "series" to "Сериалы")
            "shelf" -> listOf("" to "Вся база") + shelves.filter { type.isEmpty() || it.third == type }.map { it.first to it.second }
            "sort" -> listOf("popular" to "По популярности", "rating" to "По оценке", "year" to "Сначала новые", "title" to "По названию")
            else -> listOf("" to "Все") + facets.options(when (field) { "genre" -> "genres"; "country" -> "countries"; else -> "years" }).map { it to it }
        }
        Dialog(onDismissRequest = { dialog = null }) {
            var needle by remember(field) { mutableStateOf("") }
            Column(Modifier.width(520.dp).heightIn(max = 470.dp).background(NuvioTheme.colors.Background, RoundedCornerShape(12.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (field) { "type" -> "Тип"; "genre" -> "Жанр"; "country" -> "Страна"; "year" -> "Год"; "shelf" -> "Подборки"; else -> "Сортировка" }, color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                if (choices.size > 15) BasicTextField(value = needle, onValueChange = { needle = it }, textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 18.sp), modifier = Modifier.fillMaxWidth().border(1.dp, NuvioTheme.colors.TextSecondary).padding(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices.filter { it.second.contains(needle, ignoreCase = true) }) { (value, label) ->
                        Button(onClick = {
                            when (field) { "type" -> { type = value; genre = ""; country = ""; year = ""; shelf = "" }; "genre" -> genre = value; "country" -> country = value; "year" -> year = value; "sort" -> sort = value; "shelf" -> { shelf = value; query = ""; genre = ""; country = ""; year = ""; sort = "popular" } }
                            changed(); dialog = null
                        }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        }
    }
}

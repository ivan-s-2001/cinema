/*
 * Copyright 2023 Google LLC
 * Copyright 2026 CinemaHall contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted JetStream Compose HomeScreen, MoviesRow and Dashboard composition.
 * Original: android/tv-samples/JetStreamCompose. Catalog, state and playback
 * integration are CinemaHall code; MovieCard and focus tokens retain upstream.
 */
package app.cinemahall.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private val Canvas=Color(0xff101216)
private val Secondary=Color(0xffafb5c0)
private val Accent=Color(0xffffa447)
private val Fields="id,name_rus,name_original,description,year,rating_kinopoisk,serial,genre_name,runtime,min_age"
private val Available="playable:true AND trailer:false AND adult:false AND tor_count:[1 TO *]"

internal data class Film(val id:String,val name:String,val original:String,val description:String,val year:Int,val rating:Double,val serial:Boolean,val genres:String,val age:Int,val json:String) {
    companion object {
        fun from(data:JSONObject):Film=Film(data.optString("id"),data.optString("name_rus").ifBlank { data.optString("name_original","Без названия") },data.optString("name_original"),data.optString("description"),data.optInt("year"),data.optDouble("rating_kinopoisk",0.0),data.optBoolean("serial"),data.optString("genre_name"),data.optInt("min_age"),data.toString())
    }
    val meta:String get()=listOf(year.toString(),if(rating>0)"КП %.1f".format(java.util.Locale.ROOT,rating)else "",if(age>0)"$age+"else "").filter { it.isNotEmpty() }.joinToString("   ·   ")
}

class MainActivity:ComponentActivity() {
    private val resumeVersion=mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(background=Canvas,surface=Canvas,onSurface=Color.White,primary=Accent,onPrimary=Canvas)) {
                Shell(resumeVersion.intValue)
            }
        }
    }
    override fun onResume(){super.onResume();resumeVersion.intValue++}
    private fun watch(film:Film){
        startActivity(Intent(this,PlayerActivity::class.java).putExtra("movieId",film.id).putExtra("title",film.name).putExtra("serial",film.serial).putExtra("movieJson",film.json))
    }
    private suspend fun load(query:String,sort:String="popularity desc",offset:Int=0,count:Int=30):List<Film> = withContext(Dispatchers.IO) {
        val docs=Net.select("movie",query,sort,offset,count,Fields).getJSONArray("docs")
        (0 until docs.length()).map { Film.from(docs.getJSONObject(it)) }.filter { it.id.matches(Regex("[0-9]+")) }
    }
    private fun saved():List<Film> = runCatching {
        val array=JSONArray(getSharedPreferences("library",0).getString("favorites","[]"))
        (0 until array.length()).map { Film.from(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun continueWatching():List<Film> = getSharedPreferences("history",0).all.entries.filter { it.key.startsWith("film:") }.sortedByDescending {
        getSharedPreferences("history",0).getLong("updated:"+it.key.removePrefix("film:"),0)
    }.take(12).mapNotNull { runCatching { Film.from(JSONObject(it.value.toString())) }.getOrNull() }
    private fun saveFavorite(film:Film){
        val all=saved().toMutableList();if(all.any { it.id==film.id })all.removeAll { it.id==film.id }else all.add(0,film)
        val array=JSONArray();all.take(200).forEach { array.put(JSONObject(it.json)) };getSharedPreferences("library",0).edit().putString("favorites",array.toString()).apply()
    }
    private fun searchDialog(current:String,onSubmit:(String)->Unit){
        val field=EditText(this).apply { setText(current);hint="Название фильма или сериала";isSingleLine=true;setTextColor(android.graphics.Color.WHITE);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        val dialog=AlertDialog.Builder(this).setTitle("Поиск").setView(field).setPositiveButton("Найти"){_,_->onSubmit(field.text.toString().trim().take(160))}.setNegativeButton("Отмена",null).create()
        field.setOnEditorActionListener { _,action,_-> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){dialog.dismiss();onSubmit(field.text.toString().trim().take(160));true}else false };dialog.show();field.requestFocus()
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable private fun Shell(resume:Int){
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        var offset by remember { mutableIntStateOf(0) }
        var reload by remember { mutableIntStateOf(0) }
        var selected by remember { mutableStateOf<Film?>(null) }
        var rows by remember { mutableStateOf<List<Pair<String,List<Film>>>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var failure by remember { mutableStateOf(false) }
        var favoriteRevision by remember { mutableIntStateOf(0) }
        var restoreId by remember { mutableStateOf("") }
        var returning by remember { mutableStateOf(false) }
        val restoreFocus=remember { FocusRequester() }
        val gridState=rememberLazyGridState()
        val homeState=rememberLazyListState()
        val tabs=listOf("Главная","Фильмы","Сериалы","Поиск","Избранное")
        val favorites=remember(favoriteRevision,resume){saved()}
        val history=remember(resume){continueWatching()}
        LaunchedEffect(tab,query,offset,reload){
            loading=true;failure=false
            try {
                rows=when(tab){
                    0->coroutineScope {
                        listOf(async { "Популярное сейчас" to load("$Available AND serial:false",count=18) },async { "Новинки" to load("$Available AND serial:false","add_date desc",count=18) },async { "Сериалы" to load("$Available AND serial:true",count=18) }).awaitAll()
                    }
                    1->listOf("Фильмы" to load("$Available AND serial:false",offset=offset))
                    2->listOf("Сериалы" to load("$Available AND serial:true",offset=offset))
                    3->if(query.isBlank())emptyList()else listOf("Результаты поиска" to load("$Available AND ${Logic.searchQuery(query)}",offset=offset))
                    else->emptyList()
                }
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){rows=emptyList();failure=true}
            loading=false
        }
        BackHandler(selected!=null){selected=null;returning=true}
        BackHandler(selected==null&&tab!=0){tab=0;offset=0}
        LaunchedEffect(selected,loading,returning){if(selected==null&&!loading&&returning){withFrameNanos{};runCatching { restoreFocus.requestFocus() };returning=false}}
        Column(Modifier.fillMaxSize().background(Canvas)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=36.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("CinemaHall",fontSize=22.sp,fontWeight=FontWeight.Bold,color=Accent,modifier=Modifier.padding(end=16.dp))
                tabs.forEachIndexed { index,label->
                    Button(onClick={selected=null;offset=0;tab=index;if(index==3)searchDialog(query){query=it;reload++}},colors=ButtonDefaults.colors(containerColor=if(tab==index)Color(0xff30343c)else Color.Transparent),contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp),scale=ButtonDefaults.scale(focusedScale=1f)) { Text(label,fontSize=14.sp) }
                }
            }
            val film=selected
            if(film!=null){
                Detail(film,favorites.any { it.id==film.id },onWatch={watch(film)},onFavorite={saveFavorite(film);favoriteRevision++},onBack={selected=null;returning=true})
            }else if(loading){
                LoadingState()
            }else if(failure){
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){Text("Не удалось загрузить каталог",fontSize=24.sp);Text("Проверьте подключение к сети",color=Secondary);Button(onClick={reload++}){Text("Повторить")}}}
            }else{
                val onFilm:(Film)->Unit={restoreId=it.id;selected=it}
                if(tab==0){
                    LazyColumn(state=homeState,contentPadding=PaddingValues(bottom=48.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                        rows.firstOrNull()?.second?.firstOrNull()?.let { featured->item(key="featured"){Featured(featured,onWatch={watch(featured)},onDetails={onFilm(featured)})} }
                        if(history.isNotEmpty())item(key="continue"){Shelf("Продолжить просмотр",history,restoreId,restoreFocus,onFilm)}
                        rows.forEachIndexed { index,row->item(key="row:$index"){Shelf(row.first,row.second,restoreId,restoreFocus,onFilm)} }
                    }
                }else{
                    val films=if(tab==4)favorites else rows.firstOrNull()?.second.orEmpty()
                    Row(Modifier.fillMaxWidth().padding(horizontal=36.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(if(tab==3)if(query.isBlank())"Найдите фильм или сериал"else "Поиск: $query"else tabs[tab],fontSize=26.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        if(tab in 1..3){if(offset>0)Button(onClick={offset=(offset-30).coerceAtLeast(0)}){Text("Назад")};if(films.size==30)Button(onClick={offset+=30}){Text("Ещё")}}
                    }
                    if(films.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(tab==4)"Добавляйте фильмы в избранное на их странице"else if(query.isBlank()&&tab==3)"Выберите «Поиск» в верхнем меню"else "Ничего не найдено",color=Secondary)}
                    else LazyVerticalGrid(columns=GridCells.Adaptive(128.dp),state=gridState,contentPadding=PaddingValues(36.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp),modifier=Modifier.focusRestorer()){
                        items(films,key={it.id}){item->FilmCard(item,if(restoreId==item.id)Modifier.focusRequester(restoreFocus)else Modifier){onFilm(item)}}
                    }
                }
            }
        }
    }
    @Composable private fun LoadingState(){
        val transition=androidx.compose.animation.core.rememberInfiniteTransition(label="loading")
        val angle by transition.animateFloat(initialValue=0f,targetValue=360f,animationSpec=androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000,easing=androidx.compose.animation.core.LinearEasing)),label="spinner")
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){androidx.compose.foundation.Canvas(Modifier.size(36.dp)){drawArc(Accent,startAngle=angle,sweepAngle=270f,useCenter=false,style=androidx.compose.ui.graphics.drawscope.Stroke(width=3.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))}}
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable private fun Shelf(name:String,films:List<Film>,restoreId:String,restore:FocusRequester,onFilm:(Film)->Unit){
        Column(Modifier.focusGroup()){
            Text(name,fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=36.dp,bottom=16.dp))
            LazyRow(contentPadding=PaddingValues(horizontal=36.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),modifier=Modifier.focusRestorer()){
                items(films,key={it.id}){film->FilmCard(film,Modifier.width(132.dp).then(if(restoreId==film.id)Modifier.focusRequester(restore)else Modifier)){onFilm(film)}}
            }
        }
    }
    @Composable private fun FilmCard(film:Film,modifier:Modifier=Modifier,onClick:()->Unit){
        MovieCard(onClick=onClick,modifier=modifier,title={Column(Modifier.padding(top=8.dp)){Text(film.name,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(film.meta,fontSize=12.sp,color=Secondary,modifier=Modifier.padding(top=4.dp))}}){Poster(film,Modifier.fillMaxWidth().aspectRatio(2f/3f))}
    }
    @Composable private fun Poster(film:Film,modifier:Modifier){
        var prefix by remember(film.id){mutableIntStateOf(3)}
        Box(modifier.background(Color(0xff22262e))){
            Text(film.name,color=Secondary,fontSize=15.sp,modifier=Modifier.align(Alignment.Center).padding(12.dp),maxLines=3)
            AsyncImage(model="https://img2.zonapic.com/images/film_240/${film.id.take(prefix)}/${film.id}.jpg",contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize(),onError={if(prefix==3)prefix=4})
        }
    }
    @Composable private fun Featured(film:Film,onWatch:()->Unit,onDetails:()->Unit){
        Row(Modifier.padding(horizontal=36.dp).fillMaxWidth().height(250.dp).background(Brush.horizontalGradient(listOf(Color(0xff292934),Color(0xff171a20))),RoundedCornerShape(12.dp)),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f).padding(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("ВЫБОР НА ВЕЧЕР",fontSize=11.sp,color=Accent,letterSpacing=2.sp)
                Text(film.name,fontSize=32.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(film.meta,fontSize=14.sp,color=Secondary)
                Text(film.description,fontSize=14.sp,color=Secondary,maxLines=2,overflow=TextOverflow.Ellipsis)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick=onWatch){Text("Смотреть")};OutlinedButton(onClick=onDetails){Text("О фильме")}}
            }
            Poster(film,Modifier.fillMaxHeight().aspectRatio(2f/3f))
        }
    }
    @Composable private fun Detail(film:Film,favorite:Boolean,onWatch:()->Unit,onFavorite:()->Unit,onBack:()->Unit){
        val watchFocus=remember { FocusRequester() }
        LaunchedEffect(film.id){watchFocus.requestFocus()}
        LazyColumn(contentPadding=PaddingValues(horizontal=36.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            item { Row(horizontalArrangement=Arrangement.spacedBy(28.dp)){
                Poster(film,Modifier.width(180.dp).aspectRatio(2f/3f))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(film.name,fontSize=32.sp,fontWeight=FontWeight.Bold)
                    if(film.original!=film.name)Text(film.original,fontSize=16.sp,color=Secondary)
                    Text(film.meta,fontSize=16.sp,color=Accent)
                    if(film.genres.isNotBlank())Text(film.genres,fontSize=14.sp,color=Secondary)
                    Text(film.description,fontSize=16.sp,color=Secondary,maxLines=5,overflow=TextOverflow.Ellipsis)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick=onWatch,modifier=Modifier.focusRequester(watchFocus)){Text("Смотреть")};OutlinedButton(onClick=onFavorite){Text(if(favorite)"В избранном"else "В избранное")}}
                    OutlinedButton(onClick=onBack){Text("Назад")}
                }
            } }
            item { Text("Описание",fontSize=22.sp,fontWeight=FontWeight.SemiBold) }
            item { Text(film.description,fontSize=16.sp,color=Secondary,lineHeight=24.sp) }
        }
    }
}

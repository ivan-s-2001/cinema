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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private val Canvas=Color(0xff080b10)
private val Secondary=Color(0xffafb5c0)
private val Accent=Color(0xffd6f470)
private val Fields="id,name_rus,name_original,description,year,rating_kinopoisk,serial,genre_name,runtime,min_age,rating_imdb,country"
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
    private fun watch(film:Film,episode:String=""){
        startActivity(Intent(this,PlayerActivity::class.java).putExtra("movieId",film.id).putExtra("title",film.name).putExtra("serial",film.serial).putExtra("movieJson",film.json).putExtra("episode",episode))
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
        var genre by remember { mutableStateOf("") }
        var sort by remember { mutableStateOf("popularity desc") }
        var reload by remember { mutableIntStateOf(0) }
        var selected by remember { mutableStateOf<Film?>(null) }
        var detailStack by remember { mutableStateOf<List<Film>>(emptyList()) }
        var rows by remember { mutableStateOf<List<Pair<String,List<Film>>>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var favoriteRevision by remember { mutableIntStateOf(0) }
        var restoreId by remember { mutableStateOf("") }
        var returning by remember { mutableStateOf(false) }
        val restoreFocus=remember { FocusRequester() }
        val initialFocus=remember { FocusRequester() }
        val homeState=rememberLazyListState()
        val gridState=rememberLazyGridState()
        val tabs=listOf("Главная","Фильмы","Сериалы","Поиск","Моё")
        val icons=listOf(R.drawable.ic_home,R.drawable.ic_film,R.drawable.ic_series,R.drawable.ic_search,R.drawable.ic_heart)
        val favorites=remember(favoriteRevision,resume){saved()}
        val history=remember(resume){continueWatching()}
        LaunchedEffect(tab,query,offset,reload,genre,sort){
            loading=true
            val filter=if(genre.isEmpty())"" else " AND genre_name:\"$genre\""
            val nextRows=when(tab){
                0->supervisorScope {
                    listOf(
                        async { runCatching { "Сейчас смотрят" to load("$Available AND serial:false",count=18) }.getOrNull() },
                        async { runCatching { "Новые фильмы" to load("$Available AND serial:false","release_date desc",count=18) }.getOrNull() },
                        async { runCatching { "Сериалы, которые увлекают" to load("$Available AND serial:true",count=18) }.getOrNull() },
                        async { runCatching { "Высокий рейтинг" to load("$Available AND rating_kinopoisk:[8 TO *] AND rating_kinopoisk_count:[10000 TO *]",count=18) }.getOrNull() },
                        async { runCatching { "Для всей семьи" to load("$Available AND serial:false AND min_age:[0 TO 6]",count=18) }.getOrNull() }
                    ).awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
                }
                1,2->runCatching { listOf(tabs[tab] to load("$Available AND serial:${tab==2}$filter",sort,offset)) }.getOrDefault(emptyList())
                3->if(query.isBlank())emptyList()else runCatching { listOf("Результаты" to load("$Available AND ${Logic.searchQuery(query)}",sort,offset)) }.getOrDefault(emptyList())
                else->emptyList()
            }
            currentCoroutineContext().ensureActive()
            rows=nextRows;loading=false
        }
        BackHandler(selected!=null){if(detailStack.isNotEmpty()){selected=detailStack.last();detailStack=detailStack.dropLast(1)}else{selected=null;returning=true}}
        BackHandler(selected==null&&tab!=0){tab=0;offset=0}
        LaunchedEffect(selected,loading,returning){if(selected==null&&!loading&&returning){withFrameNanos{};runCatching { restoreFocus.requestFocus() };returning=false}}
        LaunchedEffect(Unit){withFrameNanos{};initialFocus.requestFocus()}
        Row(Modifier.fillMaxSize().background(Canvas)){
            Column(Modifier.width(88.dp).fillMaxHeight().background(Color(0xff0d1118)).padding(vertical=22.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text("CH",fontSize=23.sp,fontWeight=FontWeight.Black,color=Accent,letterSpacing=(-1).sp,modifier=Modifier.padding(bottom=32.dp))
                tabs.forEachIndexed { index,label->
                    Button(onClick={selected=null;detailStack=emptyList();offset=0;tab=index;if(index==3)searchDialog(query){query=it;reload++}},
                        modifier=Modifier.padding(bottom=6.dp).width(76.dp).then(if(index==0)Modifier.focusRequester(initialFocus)else Modifier),
                        colors=ButtonDefaults.colors(containerColor=if(tab==index)Color(0xff252d24)else Color.Transparent,contentColor=if(tab==index)Accent else Secondary,focusedContainerColor=Accent,focusedContentColor=Canvas),
                        shape=ButtonDefaults.shape(RoundedCornerShape(14.dp)),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(vertical=12.dp)){
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)){
                            Icon(painterResource(icons[index]),contentDescription=null,modifier=Modifier.size(22.dp));Text(label,fontSize=11.sp)
                        }
                    }
                }
                Spacer(Modifier.weight(1f));Text("CinemaHall",fontSize=10.sp,color=Secondary)
            }
            val film=selected
            Box(Modifier.weight(1f).fillMaxHeight()){
                if(film!=null){
                    key(film.id){Detail(film,favorites.any { it.id==film.id },onWatch={episode->watch(film,episode)},onFavorite={saveFavorite(film);favoriteRevision++},onFilm={detailStack=detailStack+film;selected=it})}
                }else if(loading){LoadingState()}
                else{
                    val onFilm:(Film)->Unit={restoreId=it.id;selected=it}
                    if(tab==0){
                        if(rows.isEmpty())ErrorState{reload++}
                        else LazyColumn(state=homeState,contentPadding=PaddingValues(bottom=40.dp),verticalArrangement=Arrangement.spacedBy(26.dp)){
                            item(key="featured"){HomeHero(rows.first().second.take(5),onFilm)}
                            if(history.isNotEmpty())item(key="continue"){Shelf("Продолжить просмотр",history,restoreId,restoreFocus,onFilm)}
                            rows.forEachIndexed { i,row->item(key="row:$i"){Shelf(row.first,row.second,restoreId,restoreFocus,onFilm)} }
                        }
                    }else{
                        val films=if(tab==4)favorites else rows.firstOrNull()?.second.orEmpty()
                        Column(Modifier.fillMaxSize()){
                            Row(Modifier.fillMaxWidth().padding(start=28.dp,end=28.dp,top=28.dp,bottom=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                Column(Modifier.weight(1f)){
                                    Text(if(tab==3&&query.isNotBlank())query else if(tab==4)"Моя коллекция"else tabs[tab],fontSize=32.sp,fontWeight=FontWeight.Bold)
                                    Text(if(tab==4)"Сохранённое для следующего вечера"else if(tab==3)"Фильмы и сериалы в одном поиске"else "Найдите то, что хочется посмотреть",fontSize=13.sp,color=Secondary,modifier=Modifier.padding(top=6.dp))
                                }
                                if(tab==3)Action("Найти",R.drawable.ic_search){searchDialog(query){query=it;offset=0;reload++}}
                            }
                            if(tab in 1..3)Row(Modifier.padding(start=28.dp,end=28.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                if(tab!=3)Action(if(genre.isEmpty())"Все жанры"else genre,R.drawable.ic_list){
                                    val labels=arrayOf("Все жанры","драма","комедия","боевик","триллер","фантастика","детектив","приключения","мультфильм")
                                    AlertDialog.Builder(this@MainActivity).setTitle("Жанр").setSingleChoiceItems(labels,labels.indexOf(genre).coerceAtLeast(0)){dialog,i->genre=if(i==0)""else labels[i];offset=0;dialog.dismiss()}.show()
                                }
                                val sortNames=listOf("Популярные","Сначала новые","По рейтингу")
                                val sorts=listOf("popularity desc","release_date desc","rating_kinopoisk desc")
                                Action(sortNames[sorts.indexOf(sort).coerceAtLeast(0)],R.drawable.ic_quality){AlertDialog.Builder(this@MainActivity).setTitle("Порядок").setSingleChoiceItems(sortNames.toTypedArray(),sorts.indexOf(sort)){dialog,i->sort=sorts[i];offset=0;dialog.dismiss()}.show()}
                                Spacer(Modifier.weight(1f))
                                if(offset>0)Action("Назад",R.drawable.ic_back){offset=(offset-30).coerceAtLeast(0)}
                                if(films.size==30)Action("Дальше",R.drawable.ic_forward){offset+=30}
                            }
                            if(films.isEmpty()){
                                if(rows.isEmpty()&&tab in 1..3&&(tab!=3||query.isNotBlank()))ErrorState{reload++}
                                else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(tab==4)"Сохраняйте фильмы кнопкой ♥ на их странице"else if(tab==3&&query.isBlank())"Введите название в поиске"else "По этим условиям ничего не найдено",color=Secondary)}
                            }else LazyVerticalGrid(columns=GridCells.Fixed(5),state=gridState,contentPadding=PaddingValues(28.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(24.dp),modifier=Modifier.focusRestorer()){
                                items(films,key={it.id}){item->FilmCard(item,if(restoreId==item.id)Modifier.focusRequester(restoreFocus)else Modifier){onFilm(item)}}
                            }
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Action(label:String,icon:Int=0,primary:Boolean=false,modifier:Modifier=Modifier,onClick:()->Unit){
        Button(onClick=onClick,modifier=modifier,scale=ButtonDefaults.scale(focusedScale=1.04f),shape=ButtonDefaults.shape(RoundedCornerShape(9.dp)),colors=ButtonDefaults.colors(containerColor=if(primary)Color.White else Color(0xff252b33),contentColor=if(primary)Canvas else Color.White,focusedContainerColor=Accent,focusedContentColor=Canvas),contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp)){
            if(icon!=0){Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp))};Text(label,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
        }
    }
    @Composable private fun LoadingState(){
        val transition=androidx.compose.animation.core.rememberInfiniteTransition(label="loading")
        val angle by transition.animateFloat(initialValue=0f,targetValue=360f,animationSpec=androidx.compose.animation.core.infiniteRepeatable(tween(1000,easing=androidx.compose.animation.core.LinearEasing)),label="spinner")
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
            androidx.compose.foundation.Canvas(Modifier.size(32.dp)){drawArc(Accent,startAngle=angle,sweepAngle=270f,useCenter=false,style=androidx.compose.ui.graphics.drawscope.Stroke(width=3.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))};Text("Собираем ваш кинотеатр",color=Secondary,fontSize=14.sp)
        }}
    }
    @Composable private fun ErrorState(retry:()->Unit){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){Text("Каталог временно недоступен",fontSize=24.sp);Text("Проверьте подключение и попробуйте ещё раз",color=Secondary);Action("Повторить",R.drawable.ic_forward,onClick=retry)}}}
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable private fun Shelf(name:String,films:List<Film>,restoreId:String="",restore:FocusRequester?=null,onFilm:(Film)->Unit){
        Column(Modifier.focusGroup()){
            Text(name,fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=28.dp,bottom=16.dp))
            LazyRow(contentPadding=PaddingValues(horizontal=28.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),modifier=Modifier.focusRestorer()){
                items(films,key={it.id}){film->FilmCard(film,Modifier.width(126.dp).then(if(restoreId==film.id&&restore!=null)Modifier.focusRequester(restore)else Modifier)){onFilm(film)}}
            }
        }
    }
    @Composable private fun FilmCard(film:Film,modifier:Modifier=Modifier,onClick:()->Unit){
        MovieCard(onClick=onClick,modifier=modifier,title={Column(Modifier.padding(top=9.dp)){Text(film.name,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${film.year}   ·   ${if(film.serial)"Сериал"else "Фильм"}",fontSize=12.sp,color=Secondary,modifier=Modifier.padding(top=4.dp))}}){
            Box{Poster(film,Modifier.fillMaxWidth().aspectRatio(2f/3f));if(film.rating>0)Text("%.1f".format(java.util.Locale.ROOT,film.rating),fontSize=12.sp,fontWeight=FontWeight.Bold,color=Canvas,modifier=Modifier.align(Alignment.TopStart).padding(7.dp).background(Accent,RoundedCornerShape(4.dp)).padding(horizontal=6.dp,vertical=3.dp))}
        }
    }
    @Composable private fun Poster(film:Film,modifier:Modifier){
        val folder=film.id.dropLast(3).ifEmpty { "0" }
        Box(modifier.background(Color(0xff222833))){
            Text(film.name,color=Secondary,fontSize=15.sp,modifier=Modifier.align(Alignment.Center).padding(12.dp),maxLines=3)
            AsyncImage(model="https://img2.zonapic.com/images/film_240/$folder/${film.id}.jpg",contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
        }
    }
    @Composable private fun enrichment(film:Film,details:Boolean=false):JSONObject{
        var meta by remember(film.id,details){mutableStateOf(JSONObject())}
        LaunchedEffect(film.id,details){meta=withContext(Dispatchers.IO){Metadata.load(this@MainActivity,JSONObject(film.json),details)}}
        return meta
    }
    @Composable private fun Artwork(film:Film,background:String,modifier:Modifier=Modifier){
        Box(modifier.background(Canvas)){
            if(background.startsWith("https://"))AsyncImage(model=background,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
            else Poster(film,Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(2f/3f))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Canvas,Canvas.copy(alpha=.93f),Canvas.copy(alpha=.2f),Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Canvas.copy(alpha=.1f),Canvas))))
        }
    }
    @Composable private fun HomeHero(films:List<Film>,onFilm:(Film)->Unit){
        var index by remember {mutableIntStateOf(0)}
        val film=films[index.coerceAtMost(films.lastIndex)]
        val meta=enrichment(film)
        Box(Modifier.fillMaxWidth().height(326.dp)){
            Crossfade(targetState=film to meta.optString("background"),animationSpec=tween(450),label="hero"){(movie,art)->Artwork(movie,art,Modifier.fillMaxSize())}
            Column(Modifier.padding(start=28.dp,top=24.dp,end=28.dp).widthIn(max=510.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("CINEMAHALL  /  ВЫБОР НА ВЕЧЕР",fontSize=10.sp,letterSpacing=2.sp,color=Accent)
                Spacer(Modifier.height(12.dp))
                Text(film.name,fontSize=38.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(film.meta+"   ·   "+if(film.serial)"Сериал"else "Фильм",fontSize=13.sp,color=Accent)
                Text(film.description,fontSize=14.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=Secondary)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Action("Смотреть",R.drawable.ic_play,true){watch(film)}
                    Action("Подробнее",R.drawable.ic_info){onFilm(film)}
                }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(end=28.dp,bottom=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text("${index+1} / ${films.size}",fontSize=12.sp,color=Secondary)
                Action("",R.drawable.ic_back){index=(index-1+films.size)%films.size}
                Action("",R.drawable.ic_forward){index=(index+1)%films.size}
            }
        }
    }
    @Composable private fun Detail(film:Film,favorite:Boolean,onWatch:(String)->Unit,onFavorite:()->Unit,onFilm:(Film)->Unit){
        val watchFocus=remember(film.id){FocusRequester()}
        val meta=enrichment(film,true)
        var related by remember(film.id){mutableStateOf<List<Film>>(emptyList())}
        var season by remember(film.id){mutableIntStateOf(1)}
        LaunchedEffect(film.id){
            watchFocus.requestFocus()
            related=runCatching {load("$Available AND serial:${film.serial} AND -id:${film.id} AND ${if(film.genres.isNotBlank())"genre_name:\"${film.genres.split(',').first().trim()}\""else "rating_kinopoisk:[7 TO *]"}",count=12)}.getOrDefault(emptyList())
        }
        val array=meta.optJSONArray("episodes")?:JSONArray()
        val episodes=(0 until array.length()).map {array.getJSONObject(it)}
        val seasons=episodes.map {it.optInt("season")}.filter {it>0}.distinct()
        LaunchedEffect(meta){if(seasons.isNotEmpty()&&season !in seasons)season=seasons.first()}
        val history=getSharedPreferences("history",0).contains("film:${film.id}")
        LazyColumn(contentPadding=PaddingValues(bottom=40.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
            item(key="detailHero"){
                Box(Modifier.fillMaxWidth().heightIn(min=375.dp)){
                    Artwork(film,meta.optString("background"),Modifier.matchParentSize())
                    Column(Modifier.padding(start=28.dp,top=34.dp,end=28.dp,bottom=16.dp).widthIn(max=610.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        Text(if(film.serial)"СЕРИАЛ"else "ФИЛЬМ",fontSize=10.sp,letterSpacing=2.sp,color=Accent)
                        Text(film.name,fontSize=40.sp,lineHeight=44.sp,fontWeight=FontWeight.Bold)
                        if(film.original.isNotBlank()&&film.original!=film.name)Text(film.original,color=Secondary,fontSize=14.sp)
                        Text(film.meta,fontSize=15.sp,color=Accent)
                        if(film.genres.isNotBlank())Text(film.genres.replaceFirstChar {it.uppercase()},fontSize=14.sp,color=Secondary)
                        Text(film.description,fontSize=15.sp,lineHeight=22.sp,maxLines=4,overflow=TextOverflow.Ellipsis,color=Color(0xffc7ccd5))
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Action(if(history)"Продолжить"else "Смотреть",R.drawable.ic_play,true,Modifier.focusRequester(watchFocus)){onWatch("")}
                            Action(if(favorite)"Сохранено"else "В избранное",if(favorite)R.drawable.ic_check else R.drawable.ic_heart,onClick=onFavorite)
                        }
                    }
                }
            }
            if(seasons.isNotEmpty()){
                item(key="seasons"){
                    Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
                        Text("Сезоны и серии",fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=28.dp))
                        LazyRow(contentPadding=PaddingValues(horizontal=28.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            items(seasons){number->Action("Сезон $number",primary=number==season){season=number}}
                        }
                    }
                }
                item(key="episodes:$season"){
                    LazyRow(contentPadding=PaddingValues(horizontal=28.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){
                        items(episodes.filter {it.optInt("season")==season},key={it.optInt("id")}){episode->
                            val aired=episode.optString("airdate").let {it.isNotEmpty()&&it<=java.time.LocalDate.now().toString()}
                            MovieCard(onClick={if(aired)onWatch("S${season}E${episode.optInt("number")}")},modifier=Modifier.width(210.dp),title={Column(Modifier.padding(top=9.dp)){
                                Text("${episode.optInt("number")}. ${episode.optString("name")}",fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(if(aired)"${episode.optInt("runtime")} мин"else "Ожидается ${episode.optString("airdate")}",fontSize=12.sp,color=Secondary,modifier=Modifier.padding(top=4.dp))
                            }}){
                                Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color(0xff222833))){
                                    Icon(painterResource(R.drawable.ic_series),null,tint=Secondary,modifier=Modifier.align(Alignment.Center).size(30.dp))
                                    AsyncImage(model=episode.optJSONObject("image")?.optString("medium"),contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
            val cast=meta.optJSONArray("cast")
            if(cast!=null&&cast.length()>0)item(key="cast"){
                Column(Modifier.padding(horizontal=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("В ролях",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    Text((0 until cast.length()).take(12).joinToString("  ·  "){cast.optString(it)},fontSize=15.sp,color=Secondary,lineHeight=23.sp)
                }
            }
            item(key="description"){Column(Modifier.padding(horizontal=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("О фильме",fontSize=21.sp,fontWeight=FontWeight.SemiBold);Text(film.description,fontSize=15.sp,color=Secondary,lineHeight=23.sp)}}
            if(related.isNotEmpty())item(key="related"){Shelf("Вам также может понравиться",related,onFilm=onFilm)}
            item(key="credits"){
                Text("Каталог: Zona  ·  Изображения и актёры: Cinemeta"+if(meta.has("tvmaze"))"  ·  Эпизоды: TVmaze, CC BY-SA 4.0\n${meta.optString("tvmaze")}"else "",fontSize=10.sp,color=Secondary,modifier=Modifier.padding(horizontal=28.dp))
            }
        }
    }
}

package app.cinemahall.tv

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.arflix.tv.data.model.*
import fi.iki.elonen.NanoHTTPD
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** CinemaHall's data adapter. All screens, navigation and playback UI belong to ARVIO. */
object CinemaIntegration {
    const val ID="cinemahall.catalog"
    const val BASE="https://cinemahall.local"
    private lateinit var context:Context
    private var server:NanoHTTPD?=null
    private val docs=ConcurrentHashMap<String,JSONObject>()
    private val types=listOf("movie","series")
    private val available="playable:true AND trailer:false AND adult:false AND tor_count:[1 TO *]"
    private val fields="id,name_rus,name_original,description,year,rating_imdb,serial,genre_name,runtime,min_age,episodes"
    val catalogs get()=listOf(
        catalog("popular","Сейчас смотрят","movie"),catalog("new","Новые фильмы","movie"),
        catalog("series","Сериалы","series"),catalog("top","Высокий рейтинг","movie")
    )
    private fun catalog(id:String,title:String,type:String):CatalogConfig {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest("$ID|$type|$id".toByteArray()).take(8).joinToString(""){"%02x".format(it)}
        return CatalogConfig("addon_$hash",title,CatalogSourceType.ADDON,addonId=ID,addonCatalogType=type,addonCatalogId=id,addonName="CinemaHall")
    }
    private fun manifest()=AddonManifest(ID,"CinemaHall","0.4.0","Каталог фильмов и сериалов",types=types,
        resources=listOf(AddonResource("catalog",types),AddonResource("meta",types,listOf("ch:")),AddonResource("stream",types,listOf("ch:"))),
        idPrefixes=listOf("ch:"),catalogs=catalogs.map {AddonCatalog(it.addonCatalogType!!,it.addonCatalogId!!,it.title,extra=listOf(AddonCatalogExtra("skip"),AddonCatalogExtra("search")))})
    fun addon()=Addon(ID,"CinemaHall","0.4.0","Фильмы и сериалы",true,true,AddonType.CUSTOM,url="$BASE/manifest.json",transportUrl=BASE,manifest=manifest())
    @Synchronized fun initialize(app:Context){
        context=app.applicationContext?:app
        if(server!=null)return
        server=object:NanoHTTPD("127.0.0.1",18991){
            override fun serve(session:IHTTPSession):NanoHTTPD.Response {
                return try {
                    val parts=session.uri.trim('/').split('/')
                    require(parts.size==4&&parts[0]=="play")
                    val id=parts[1];require(id.matches(Regex("[0-9]+")))
                    val season=parts[2].toInt();val episode=parts[3].toInt()
                    require(season in 0..999&&episode in 0..9999)
                    val quality=session.parameters["quality"]?.firstOrNull().orEmpty()
                    require(quality in listOf("","4K","2K","1080p","720p","576p","480p","360p","240p"))
                    val voice=session.parameters["voice"]?.firstOrNull().orEmpty()
                    val translation=if(voice.isEmpty())""else String(Base64.decode(voice,Base64.URL_SAFE or Base64.NO_WRAP),Charsets.UTF_8)
                    require(translation.length<500)
                    val url=prepare(id,season,episode,quality,translation)
                    newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT,"text/plain","").apply {addHeader("Location",url)}
                }catch(_:Exception){newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,"text/plain","Просмотр сейчас недоступен")}
            }
        }.also {it.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)}
    }
    val interceptor=Interceptor { chain ->
        val request=chain.request()
        val host=request.url.host
        if(host.endsWith("supabase.co")||host.endsWith("themoviedb.org")||host=="auth.arvio.tv"||host=="sync.arvio.tv") {
            return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("Configure your own integration").body("{}".toResponseBody("application/json".toMediaType())).build()
        }
        if(host!="cinemahall.local")return@Interceptor chain.proceed(request)
        val result=runCatching {route(request.url.pathSegments)}
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(result.isSuccess)200 else 503).message("CinemaHall")
            .body((result.getOrNull()?:"{\"error\":\"Каталог временно недоступен\"}").toResponseBody("application/json; charset=utf-8".toMediaType())).build()
    }
    private fun route(path:List<String>):String {
        return when(path.firstOrNull()){
            "manifest.json"->com.google.gson.Gson().toJson(manifest())
            "catalog"->{
                val type=path.getOrNull(1).orEmpty();require(type in types)
                val catalog=path.getOrNull(2).orEmpty().removeSuffix(".json")
                val extra=path.getOrNull(3).orEmpty().removeSuffix(".json").split('&').mapNotNull {part->val pair=part.split('=',limit=2);if(pair.size==2)pair[0] to java.net.URLDecoder.decode(pair[1],"UTF-8")else null}.toMap()
                val query=extra["search"]?.take(160)?.let {" AND ${Logic.searchQuery(it)}"}.orEmpty()
                val skip=extra["skip"]?.toIntOrNull()?.coerceIn(0,100000)?:0
                val serial=if(type=="series")"true"else "false"
                val high=if(catalog=="top")" AND rating_kinopoisk:[8 TO *]"else ""
                val data=Net.select("movie","$available AND serial:$serial$high$query",if(catalog=="new")"release_date desc"else "popularity desc",skip,40,fields).getJSONArray("docs")
                val metas=JSONArray()
                for(i in 0 until data.length()){
                    val doc=data.getJSONObject(i);docs[doc.getString("id")]=doc
                    metas.put(meta(doc,false,i==0))
                }
                JSONObject().put("metas",metas).toString()
            }
            "meta"->{val id=path.last().removeSuffix(".json").removePrefix("ch:");require(id.matches(Regex("[0-9]+")));JSONObject().put("meta",meta(document(id),true,true)).toString()}
            "stream"->{
                val identity=path.last().removeSuffix(".json").removePrefix("ch:").split(':')
                val id=identity[0];require(id.matches(Regex("[0-9]+")))
                val season=identity.getOrNull(1)?.toIntOrNull()?:0;val episode=identity.getOrNull(2)?.toIntOrNull()?:0
                val sources=Sources.ordered(Sources.load(id,if(season>0)"S${season}E$episode"else ""),"","","1080p")
                val streams=JSONArray();val seen=HashSet<String>()
                fun stream(q:String,t:String):JSONObject {
                    val voice=Base64.encodeToString(t.toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    return JSONObject().put("name",if(q.isEmpty())"Авто"else q).put("title",if(t.isEmpty())"Автоматический выбор озвучки"else t)
                        .put("url","http://127.0.0.1:18991/play/$id/$season/$episode?quality=$q&voice=$voice")
                }
                if(sources.isNotEmpty())streams.put(stream("",""))
                for(source in sources)if(source.quality!="Не указано"&&seen.add(source.quality+":"+source.translation))streams.put(stream(source.quality,source.translation))
                JSONObject().put("streams",streams).toString()
            }
            else->throw IllegalArgumentException("Unsupported route")
        }
    }
    private fun document(id:String):JSONObject=docs[id]?:Net.select("movie","id:$id","id asc",0,1,fields).getJSONArray("docs").getJSONObject(0).also {docs[id]=it}
    private fun meta(doc:JSONObject,full:Boolean,art:Boolean):JSONObject {
        val id=doc.getString("id");val serial=doc.optBoolean("serial")
        val enriched=if(art||full)Metadata.load(context,doc,full)else JSONObject()
        val poster="https://img2.zonapic.com/images/film_240/${id.dropLast(3).ifEmpty {"0"}}/$id.jpg"
        val result=JSONObject().put("id","ch:$id").put("type",if(serial)"series"else "movie").put("name",doc.optString("name_rus").ifBlank {doc.optString("name_original")})
            .put("poster",enriched.optString("poster").ifBlank {poster}).put("background",enriched.optString("background"))
            .put("description",doc.optString("description")).put("releaseInfo",doc.optInt("year").toString()).put("year",doc.optInt("year").toString())
            .put("genres",JSONArray(doc.optString("genre_name").split(',').map {it.trim()}.filter {it.isNotBlank()}))
        if(full&&serial){
            val videos=JSONArray();val episodes=enriched.optJSONArray("episodes")?:JSONArray()
            for(i in 0 until episodes.length()){
                val ep=episodes.getJSONObject(i);val s=ep.optInt("season");val e=ep.optInt("number");if(s<1||e<1)continue
                videos.put(JSONObject().put("id","ch:$id:$s:$e").put("season",s).put("episode",e).put("title",ep.optString("name"))
                    .put("released",ep.optString("airdate").takeIf {it.isNotEmpty()}?.plus("T00:00:00.000Z"))
                    .put("thumbnail",ep.optJSONObject("image")?.optString("original")))
            }
            if(videos.length()==0)Regex("S(\\d+)E(\\d+)",RegexOption.IGNORE_CASE).findAll(doc.optString("episodes")).forEach {
                val s=it.groupValues[1].toInt();val e=it.groupValues[2].toInt()
                videos.put(JSONObject().put("id","ch:$id:$s:$e").put("season",s).put("episode",e).put("title","Серия $e"))
            }
            result.put("videos",videos)
            if(enriched.has("tvmaze"))result.put("description",doc.optString("description")+"\n\nЭпизоды: TVmaze (CC BY-SA 4.0), "+enriched.optString("tvmaze"))
        }
        return result
    }
    private var activeKey=""
    private var activeHash=""
    private var activeUrl=""
    @Synchronized private fun prepare(id:String,season:Int,episode:Int,quality:String,translation:String):String {
        val key="$id:$season:$episode:$quality:$translation"
        if(key==activeKey&&TorrentService.ready)return activeUrl
        context.startForegroundService(Intent(context,TorrentService::class.java))
        val deadline=System.currentTimeMillis()+45000
        while(!TorrentService.ready){check(TorrentService.failure==null&&System.currentTimeMillis()<deadline);Thread.sleep(200)}
        val episodeKey=if(season>0)"S${season}E$episode"else ""
        val display=context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        val television=context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val target=if(television&&display.supportedModes.any {maxOf(it.physicalWidth,it.physicalHeight)>=3800})"4K"else "1080p"
        val candidates=Sources.ordered(Sources.load(id,episodeKey),quality,translation,target)
        for(candidate in candidates.take(6)){
            var acquired=""
            try {
                var data=Net.json(Net.ENGINE+"/torrents",JSONObject().put("action","add").put("link",candidate.link).put("save_to_db",false))
                acquired=data.getString("hash")
                val until=System.currentTimeMillis()+15000
                var files=data.optJSONArray("file_stats")?:JSONArray()
                while(files.length()==0&&System.currentTimeMillis()<until){Thread.sleep(400);data=Net.json(Net.ENGINE+"/torrents",JSONObject().put("action","get").put("hash",acquired));files=data.optJSONArray("file_stats")?:JSONArray()}
                val playable=(0 until files.length()).map {files.getJSONObject(it)}.filter {Logic.isVideo(it.optString("path"))&&!it.optString("path").contains("sample",true)}
                val file=if(season>0)playable.firstOrNull {Logic.episodeKey(it.optString("path"))==episodeKey||it.optString("path").substringAfterLast('/')==candidate.episodeFile(episodeKey).substringAfterLast('/')}else playable.maxByOrNull {it.optLong("length")}
                requireNotNull(file)
                val url=Net.ENGINE+"/stream/video?link=${Net.enc(acquired)}&index=${file.getInt("id")}&play&preload"
                if(activeHash.isNotEmpty()&&activeHash!=acquired)runCatching {Net.json(Net.ENGINE+"/torrents",JSONObject().put("action","rem").put("hash",activeHash))}
                activeKey=key;activeHash=acquired;activeUrl=url;return url
            }catch(_:Exception){if(acquired.isNotEmpty())runCatching {Net.json(Net.ENGINE+"/torrents",JSONObject().put("action","rem").put("hash",acquired))}}
        }
        error("No available video")
    }
}

package app.cinemahall.tv;

import android.content.*;
import org.json.*;
import java.util.*;
import java.net.URLDecoder;

/** Own catalogue/metadata/search/streams behind the clients' normal add-on contract. */
final class CinemaCatalog {
    static final String[][] ROWS={{"popular","Сейчас смотрят","movie"},{"series","Популярные сериалы","series"},{"new","Новые фильмы","movie"},{"new_series","Новые сериалы","series"},{"top","Лучшие фильмы","movie"},{"top_series","Лучшие сериалы","series"},{"animation","Мультфильмы","movie"},{"scifi","Фантастика","movie"}};
    static final String AVAILABLE="playable:true AND trailer:false AND adult:false AND tor_count:[1 TO *]";
    static final String FIELDS="id,name_rus,name_eng,name_original,description,year,rating_imdb,serial,genre_name,runtime,episodes";
    private final Context context;
    private final String base,playToken=UUID.randomUUID().toString();
    private final CinemaSnapshot snapshot;
    private final Map<String,JSONObject> docs=Collections.synchronizedMap(new LinkedHashMap<String,JSONObject>(128,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,JSONObject> eldest){return size()>120;}});
    static final class Reply {final String body,location;Reply(String body){this.body=body;this.location=null;}Reply(String body,String location){this.body=body;this.location=location;}}
    CinemaCatalog(Context context){this.context=context;this.base=CinemaServer.base(context);snapshot=new CinemaSnapshot(context);}
    static JSONObject manifest() throws JSONException {
        JSONArray rows=new JSONArray();for(String[] row:ROWS)rows.put(new JSONObject().put("id",row[0]).put("name",row[1]).put("type",row[2]).put("pageSize",24).put("showInHome",true).put("extra",new JSONArray().put(new JSONObject().put("name","skip")).put(new JSONObject().put("name","search"))));
        return new JSONObject().put("id","app.cinemahall.catalog").put("name","CinemaHall").put("version","0.6.0")
            .put("description","Каталог, метаданные и источники CinemaHall").put("types",new JSONArray(Arrays.asList("movie","series")))
            .put("idPrefixes",new JSONArray(Arrays.asList("ch:"))).put("resources",new JSONArray(Arrays.asList("catalog","meta","stream","subtitles"))).put("catalogs",rows);
    }
    Reply route(String rawPath) throws Exception {
        if(rawPath==null||rawPath.length()>4096)throw new Exception("Invalid path");
        String[] parts=rawPath.replaceFirst("^/","").split("/");for(int i=0;i<parts.length;i++)if(i!=3||!parts[0].equals("catalog"))parts[i]=URLDecoder.decode(parts[i],"UTF-8");
        if(parts.length==1&&parts[0].equals("manifest.json"))return new Reply(manifest().toString());
        if(parts.length>=3&&parts[0].equals("play"))return play(parts);
        if(parts.length<3||parts.length>4||!Arrays.asList("movie","series").contains(parts[1]))throw new Exception("Invalid route");
        String id=parts[2].replaceFirst("\\.json$","");
        if(parts[0].equals("catalog")){
            boolean valid=false;for(String[] row:ROWS)if(row[0].equals(id)&&row[2].equals(parts[1]))valid=true;if(!valid)throw new Exception("Invalid category");
            Map<String,String> args=new HashMap<>();if(parts.length==4)for(String value:parts[3].replaceFirst("\\.json$","").split("&")){String[] pair=value.split("=",2);if(pair.length==2)args.put(pair[0],URLDecoder.decode(pair[1],"UTF-8"));}
            int skip=0;try{skip=Integer.parseInt(args.getOrDefault("skip","0"));}catch(NumberFormatException invalid){throw new Exception("Invalid page");}
            if(skip<0||skip>100000)throw new Exception("Invalid page");String search=args.getOrDefault("search","");if(search.length()>160)throw new Exception("Query too long");
            JSONObject data=snapshot.catalog(parts[1],id,search,skip);return new Reply((data!=null?data:catalog(parts[1],id,search,skip)).toString());
        }
        if(parts[0].equals("meta")){
            JSONObject bundled=snapshot.detail(id);
            if(bundled!=null&&parts[1].equals("series")&&bundled.getJSONArray("videos").length()==0){
                JSONObject source=snapshot.document(id);
                if(source!=null&&source.optString("id").matches("[0-9]{1,12}"))try{
                    JSONObject live=meta(document("ch:"+source.getString("id")),true);JSONArray videos=live.optJSONArray("videos");
                    if(videos!=null)for(int i=0;i<videos.length();i++){JSONObject ep=videos.getJSONObject(i);ep.put("id",id+":"+ep.getInt("season")+":"+ep.getInt("episode"));}
                    bundled.put("videos",videos);
                    if(live.optString("description").contains("TVmaze"))bundled.put("description",live.getString("description"));
                }catch(Exception optional){} // Keep the base card if optional episode enrichment fails.
            }
            return new Reply(new JSONObject().put("meta",bundled!=null?bundled:meta(document(id),true)).toString());
        }
        if(parts[0].equals("subtitles"))return new Reply("{\"subtitles\":[]}");
        if(parts[0].equals("stream")){
            String[] identity=identity(id);String titleId=identity[0],episode=identity[1];
            JSONObject film=snapshot.document(titleId);if(film==null)film=document(titleId);
            // The optional Torznab endpoint lives in our own embedded engine.
            engine();ArrayList<String> names=new ArrayList<>();for(String field:new String[]{"name_rus","name_original","name_eng"})names.add(film.optString(field));
            List<Sources.Candidate> candidates=Sources.load(film.optString("id"),episode,names,film.optInt("year"));
            JSONArray streams=new JSONArray();
            for(Map.Entry<String,LinkedHashMap<String,ArrayList<Sources.Candidate>>> q:Sources.grouped(candidates,"1080p").entrySet())for(ArrayList<Sources.Candidate> voices:q.getValue().values()){
                Sources.Candidate candidate=voices.get(0);String voice=candidate.translation;
                String url=base+"/play/"+playToken+"/"+Net.enc(titleId)+"/"+Net.enc(episode)+"/"+Net.enc(q.getKey())+"/"+Net.enc(Logic.voiceKey(voice))+"/video.mkv";
                streams.put(new JSONObject().put("name","CinemaHall · "+q.getKey()).put("title",(voice.isEmpty()?"Озвучка не указана":voice)+"\nКачество заявлено источником").put("url",url)
                    .put("behaviorHints",new JSONObject().put("bingeGroup","cinemahall:"+q.getKey()+":"+Logic.voiceKey(voice))));
            }
            return new Reply(new JSONObject().put("streams",streams).toString());
        }
        throw new Exception("Unknown route");
    }
    static String[] identity(String id) throws Exception {
        if(id==null||id.length()>160||!id.matches("ch:[A-Za-z0-9:_-]+"))throw new Exception("Invalid identity");
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(ch:.*):([0-9]{1,3}):([0-9]{1,4})$").matcher(id);
        if(match.matches())return new String[]{match.group(1),"S"+Integer.parseInt(match.group(2))+"E"+Integer.parseInt(match.group(3))};
        return new String[]{id,""};
    }
    private JSONObject catalog(String type,String category,String search,int skip) throws Exception {
        String filter=AVAILABLE+" AND serial:"+type.equals("series");
        if(category.equals("animation"))filter+=" AND genre_name:\"мультфильм\"";if(category.equals("scifi"))filter+=" AND genre_name:\"фантастика\"";
        if(category.startsWith("top"))filter+=" AND rating_kinopoisk:[8 TO *]";if(!search.trim().isEmpty())filter+=" AND "+Logic.searchQuery(search);
        JSONObject result=Net.select("movie",filter,category.startsWith("new")?"release_date desc":"popularity desc",skip,24,FIELDS);JSONArray rows=new JSONArray(),values=result.getJSONArray("docs");
        for(int i=0;i<values.length();i++){JSONObject film=values.getJSONObject(i);docs.put("ch:"+film.getString("id"),film);rows.put(meta(film,false));}
        return new JSONObject().put("metas",rows).put("hasMore",skip+values.length()<result.optInt("numFound"));
    }
    private JSONObject document(String id) throws Exception {
        if(!id.matches("ch:[0-9]{1,12}"))throw new Exception("Unknown identity");JSONObject old=docs.get(id);if(old!=null)return old;
        JSONObject film=Net.select("movie","id:"+id.substring(3)+" AND adult:false","id asc",0,1,FIELDS).getJSONArray("docs").getJSONObject(0);docs.put(id,film);return film;
    }
    private JSONObject meta(JSONObject film,boolean full) throws Exception {
        String id=film.getString("id");JSONObject art=full?Metadata.load(context,film,true):new JSONObject();
        JSONObject result=new JSONObject().put("id","ch:"+id).put("type",film.optBoolean("serial")?"series":"movie")
            .put("name",film.optString("name_rus").isEmpty()?film.optString("name_original"):film.optString("name_rus"))
            .put("poster",art.optString("poster").isEmpty()?"https://img2.zonapic.com/images/film_240/"+(id.length()>3?id.substring(0,id.length()-3):"0")+"/"+id+".jpg":art.optString("poster"))
            .put("background",art.optString("background")).put("description",film.optString("description")).put("releaseInfo",film.optString("year")).put("imdbRating",film.optString("rating_imdb"));
        JSONArray genres=new JSONArray();for(String g:film.optString("genre_name").split(","))if(!g.trim().isEmpty())genres.put(g.trim());result.put("genres",genres);
        if(full&&film.optBoolean("serial")){
            JSONArray videos=new JSONArray(),episodes=art.optJSONArray("episodes");HashSet<String> seen=new HashSet<>();
            if(episodes!=null)for(int i=0;i<episodes.length();i++){
                JSONObject ep=episodes.getJSONObject(i);int s=ep.optInt("season"),e=ep.optInt("number");if(s<1||e<1)continue;seen.add("S"+s+"E"+e);
                JSONObject video=new JSONObject().put("id","ch:"+id+":"+s+":"+e).put("season",s).put("episode",e).put("title",ep.optString("name")).put("overview",android.text.Html.fromHtml(ep.optString("summary"),0).toString());
                if(ep.optJSONObject("image")!=null)video.put("thumbnail",ep.getJSONObject("image").optString("original"));
                if(!ep.optString("airdate").isEmpty())video.put("released",ep.optString("airdate")+"T00:00:00.000Z");videos.put(video);
            }
            java.util.regex.Matcher keys=java.util.regex.Pattern.compile("(?i)S([0-9]{1,3})E([0-9]{1,4})").matcher(film.optString("episodes"));
            while(keys.find()){int s=Integer.parseInt(keys.group(1)),e=Integer.parseInt(keys.group(2));if(s>0&&e>0&&seen.add("S"+s+"E"+e))videos.put(new JSONObject().put("id","ch:"+id+":"+s+":"+e).put("season",s).put("episode",e).put("title","Серия "+e));}
            result.put("videos",videos);if(art.has("tvmaze"))result.put("description",result.optString("description")+"\n\nЭпизоды: TVmaze (CC BY-SA 4.0), "+art.optString("tvmaze"));
        }
        return result;
    }
    private void engine() throws Exception {
        if(TorrentService.ready)return;context.startForegroundService(new Intent(context,TorrentService.class));long until=System.currentTimeMillis()+20000;
        while(!TorrentService.ready&&System.currentTimeMillis()<until){Thread.sleep(200);if(TorrentService.failure!=null)throw new Exception(TorrentService.failure);}
        if(!TorrentService.ready)throw new Exception("Engine unavailable");
    }
    private synchronized Reply play(String[] parts) throws Exception {
        if(parts.length!=7||!parts[1].equals(playToken)||!parts[6].equals("video.mkv"))throw new Exception("Invalid playback token");
        String[] parsed=identity(parts[2]);if(!parsed[1].isEmpty()||(!parts[3].isEmpty()&&!parts[3].matches("S[0-9]{1,3}E[0-9]{1,4}")))throw new Exception("Invalid playback identity");
        JSONObject film=snapshot.document(parts[2]);if(film==null)film=document(parts[2]);engine();
        ArrayList<String> names=new ArrayList<>();for(String field:new String[]{"name_rus","name_original","name_eng"})names.add(film.optString(field));
        List<Sources.Candidate> candidates=Sources.ordered(Sources.load(film.optString("id"),parts[3],names,film.optInt("year")),parts[4],parts[5],"1080p");
        // last segment is video container hint; supplied by our own stream URL only.
        Exception failure=null;
        for(Sources.Candidate candidate:candidates.subList(0,Math.min(4,candidates.size()))){String hash="";
            try{
                JSONObject added=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","add").put("link",candidate.link).put("save_to_db",false));hash=added.getString("hash");JSONArray files=added.optJSONArray("file_stats");
                for(int i=0;(files==null||files.length()==0)&&i<30;i++){Thread.sleep(400);files=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","get").put("hash",hash)).optJSONArray("file_stats");}
                JSONObject selected=null;long size=-1;
                if(files!=null)for(int i=0;i<files.length();i++){
                    JSONObject file=files.getJSONObject(i);String path=file.optString("path");if(!Logic.isVideo(path)||path.toLowerCase(Locale.ROOT).contains("sample"))continue;
                    if(parts[3].isEmpty()){if(file.optLong("length")>size){size=file.optLong("length");selected=file;}}
                    else if(parts[3].equals(Logic.episodeKey(path))||(!candidate.episodeFile(parts[3]).isEmpty()&&path.endsWith(candidate.episodeFile(parts[3])))){selected=file;break;}
                }
                if(selected==null)throw new Exception("Episode not found");
                return new Reply("",Net.ENGINE+"/stream/video?link="+Net.enc(hash)+"&index="+selected.getInt("id")+"&play");
            }catch(Exception error){failure=error;if(!hash.isEmpty())try{Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","rem").put("hash",hash));}catch(Exception ignored){}}
        }
        throw new Exception("No playable source",failure);
    }
}

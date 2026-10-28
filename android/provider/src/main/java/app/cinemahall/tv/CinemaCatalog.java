package app.cinemahall.tv;

import android.content.*;
import org.json.*;
import java.util.*;
import java.net.URLDecoder;

/** Own catalogue/metadata/search/streams behind the clients' normal add-on contract. */
final class CinemaCatalog {
    static final String[][] ROWS={{"popular","Популярные фильмы","movie","true"},{"series","Популярные сериалы","series","true"},{"new","Новые фильмы","movie","true"},{"new_series","Новые сериалы","series","true"},{"thriller","Триллеры","movie","true"},{"comedy","Комедии","movie","true"},{"scifi","Фантастика","movie","true"},{"animation","Мультфильмы","movie","true"},{"top","Лучшие фильмы","movie","true"},{"top_series","Лучшие сериалы","series","true"},{"new_thriller","Новые триллеры","movie","false"},{"new_comedy","Новые комедии","movie","false"},{"new_scifi","Новая фантастика","movie","false"},{"drama","Драмы","movie","false"},{"new_drama","Новые драмы","movie","false"},{"crime","Криминальные фильмы","movie","false"},{"new_crime","Новые криминальные фильмы","movie","false"},{"adventure","Приключения","movie","false"},{"new_adventure","Новые приключения","movie","false"},{"new_animation","Новые мультфильмы","movie","false"},{"detective","Детективы","movie","false"},{"horror","Ужасы","movie","false"},{"fantasy","Фэнтези","movie","false"},{"family","Семейное кино","movie","false"},{"romance","Мелодрамы","movie","false"},{"history","Исторические фильмы","movie","false"},{"war","Военные фильмы","movie","false"},{"documentary","Документальное кино","movie","false"},{"music","Музыка и мюзиклы","movie","false"},{"short_evening","До полутора часов","movie","false"},{"epics","Большое кино: от трёх часов","movie","false"},{"classic","Классика кино","movie","true"},{"nineties","Кино 90-х","movie","false"},{"two_thousands","Кино 2000-х","movie","false"},{"space","Космос и другие миры","movie","true"},{"heists","Ограбления и аферы","movie","true"},{"survival","Борьба за выживание","movie","false"},{"detective_series","Детективные сериалы","series","true"},{"comedy_series","Комедийные сериалы","series","false"},{"fantasy_series","Фантастика и фэнтези: сериалы","series","false"}};
    private final Context context;
    private final String base,playToken=UUID.randomUUID().toString();
    private final CinemaSnapshot snapshot;
    private final CinemaRemoteCatalog remote=new CinemaRemoteCatalog();
    static final class Reply {final String body,location;Reply(String body){this.body=body;this.location=null;}Reply(String body,String location){this.body=body;this.location=location;}}
    CinemaCatalog(Context context){this.context=context;this.base=CinemaServer.base(context);snapshot=new CinemaSnapshot(context);}
    static JSONObject manifest() throws JSONException {
        JSONArray rows=new JSONArray();for(String[] row:ROWS)rows.put(new JSONObject().put("id",row[0]).put("name",row[1]).put("type",row[2]).put("pageSize",24).put("showInHome",Boolean.parseBoolean(row[3])).put("extra",new JSONArray().put(new JSONObject().put("name","skip")).put(new JSONObject().put("name","search"))));
        return new JSONObject().put("id","app.cinemahall.catalog").put("name","CinemaHall").put("version","0.7.4")
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
            JSONObject data=remote.catalog(parts[1],id,search,skip);

            if(data==null)throw new Exception("Сервер каталога временно недоступен");
            return new Reply(data.toString());
        }
        if(parts[0].equals("meta")){
            JSONObject bundled=remote.detail(id);
            if(bundled==null)bundled=snapshot.detail(id);
            if(bundled==null)throw new Exception("Метаданные временно недоступны");
            return new Reply(new JSONObject().put("meta",bundled).toString());
        }
        if(parts[0].equals("subtitles"))return new Reply("{\"subtitles\":[]}");
        if(parts[0].equals("stream")){
            String[] identity=identity(id);String titleId=identity[0],episode=identity[1];
            List<Sources.Candidate> candidates=CinemaRemoteSources.load(titleId,episode);
            if(candidates==null)throw new Exception("Сервер поиска источников временно недоступен");
            JSONArray streams=new JSONArray();
            JSONArray qualities=new JSONArray();
            // A single ready-to-play option first; detailed quality/voice choices remain available.
            ArrayList<Sources.Candidate> preferred=Sources.ordered(candidates,"","","1080p");
            if(!preferred.isEmpty()){
                Sources.Candidate best=preferred.get(0);
                String voiceKey=Logic.voiceKey(best.translation);
                String autoUrl=base+"/play/"+playToken+"/"+Net.enc(titleId)+"/"+Net.enc(episode)+"/auto/auto/video.mkv";
                streams.put(new JSONObject().put("name","CinemaHall · Авто")
                    .put("title","Смотреть · рекомендуемое")
                    .put("url",autoUrl).put("technologyHints",new JSONArray(best.technologyHints))
                    .put("hintsVerified",false)
                    .put("behaviorHints",new JSONObject().put("bingeGroup","cinemahall:auto")));
            }
            for(Map.Entry<String,LinkedHashMap<String,ArrayList<Sources.Candidate>>> q:Sources.grouped(candidates,"1080p").entrySet()){
                JSONArray voiceOptions=new JSONArray();
                for(ArrayList<Sources.Candidate> voices:q.getValue().values()){
                    Sources.Candidate candidate=voices.get(0);
                    String voice=candidate.translation;
                    String voiceKey=Logic.voiceKey(voice);
                    String voiceLabel=voice.isEmpty()?"Озвучка не указана":voice;
                    String url=base+"/play/"+playToken+"/"+Net.enc(titleId)+"/"+Net.enc(episode)+"/"+Net.enc(q.getKey())+"/"+Net.enc(voiceKey)+"/video.mkv";
                    voiceOptions.put(new JSONObject().put("id",voiceKey).put("name",voiceLabel).put("sourceCount",voices.size()).put("streamIndex",streams.length())
                        .put("technologyHints",new JSONArray(candidate.technologyHints)).put("hintsVerified",false));
                    // Existing Nuvio clients still receive their standard stream contract.
                    streams.put(new JSONObject().put("name","CinemaHall · "+q.getKey()).put("title",voiceLabel).put("url",url)
                        .put("technologyHints",new JSONArray(candidate.technologyHints)).put("hintsVerified",false)
                        .put("behaviorHints",new JSONObject().put("bingeGroup","cinemahall:"+q.getKey()+":"+voiceKey)));
                }
                qualities.put(new JSONObject().put("id",q.getKey()).put("label",q.getKey()).put("voices",voiceOptions));
            }
            // CinemaHall's own UI uses qualities[]; the native Nuvio adapter uses streams[].
            return new Reply(new JSONObject().put("streams",streams).put("qualities",qualities)
                .put("automatic",streams.length()>0).put("availableReleases",candidates.size())
                .put("discoveryComplete",CinemaRemoteSources.complete(titleId,episode))
                .put("recommendedQuality",preferred.isEmpty()?"":preferred.get(0).quality)
                .put("recommendedVoice",preferred.isEmpty()?"":preferred.get(0).translation)
                .toString());
        }
        throw new Exception("Unknown route");
    }
    static String[] identity(String id) throws Exception {
        if(id==null||id.length()>160||!id.matches("ch:[A-Za-z0-9:_-]+"))throw new Exception("Invalid identity");
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(ch:.*):([0-9]{1,3}):([0-9]{1,4})$").matcher(id);
        if(match.matches())return new String[]{match.group(1),"S"+Integer.parseInt(match.group(2))+"E"+Integer.parseInt(match.group(3))};
        return new String[]{id,""};
    }
    /** Prefer the episode's own still over a duplicated show-wide background. */
    static String episodeStill(JSONObject episode){
        if(episode==null)return "";
        JSONObject image=episode.optJSONObject("image");
        for(String url:new String[]{
            episode.optString("still"),episode.optString("thumbnail"),
            image==null?"":image.optString("original"),
            image==null?"":image.optString("medium"),
            image==null?"":image.optString("url")
        }){
            if(url.startsWith("https://")||url.startsWith("http://"))return url;
        }
        return "";
    }
    /** Use the series backdrop only when an episode still is unavailable. */
    static void fillEpisodeArtwork(JSONArray videos,String background,String poster) throws JSONException {
        if(background==null||background.isEmpty()||background.equals(poster))return;
        for(int i=0;i<videos.length();i++){
            JSONObject video=videos.optJSONObject(i);
            if(video!=null&&video.optString("thumbnail").trim().isEmpty())video.put("thumbnail",background);
        }
    }
    static void mergeKnownEpisodes(String titleId,JSONArray videos,String source) throws Exception {
        identity(titleId);HashSet<String> known=new HashSet<>();
        for(int i=0;i<videos.length();i++){JSONObject ep=videos.getJSONObject(i);known.add("S"+ep.getInt("season")+"E"+ep.getInt("episode"));}
        java.util.regex.Matcher keys=java.util.regex.Pattern.compile("(?i)S([0-9]{1,3})E([0-9]{1,4})").matcher(source);
        while(keys.find()){int s=Integer.parseInt(keys.group(1)),e=Integer.parseInt(keys.group(2));
            if(s>0&&e>0&&known.add("S"+s+"E"+e))videos.put(new JSONObject().put("id",titleId+":"+s+":"+e).put("season",s).put("episode",e).put("title","Серия "+e));}
    }
    private void engine() throws Exception {
        if(TorrentService.ready)return;context.startForegroundService(new Intent(context,TorrentService.class));long until=System.currentTimeMillis()+20000;
        while(!TorrentService.ready&&System.currentTimeMillis()<until){Thread.sleep(200);if(TorrentService.failure!=null)throw new Exception(TorrentService.failure);}
        if(!TorrentService.ready)throw new Exception("Engine unavailable");
    }
    private synchronized Reply play(String[] parts) throws Exception {
        if(parts.length!=7||!parts[1].equals(playToken)||!parts[6].equals("video.mkv"))throw new Exception("Invalid playback token");
        String[] parsed=identity(parts[2]);if(!parsed[1].isEmpty()||(!parts[3].isEmpty()&&!parts[3].matches("S[0-9]{1,3}E[0-9]{1,4}")))throw new Exception("Invalid playback identity");
        boolean automatic="auto".equals(parts[4])&&"auto".equals(parts[5]);
        List<Sources.Candidate> available=CinemaRemoteSources.load(parts[2],parts[3]);
        if(available==null||available.isEmpty())throw new Exception("Сервер не нашёл доступных источников");
        engine();
        List<Sources.Candidate> candidates=automatic
            ? Sources.automaticFallbacks(available,"1080p")
            : Sources.ordered(available,parts[4],parts[5],"1080p");
        // last segment is video container hint; supplied by our own stream URL only.
        Exception failure=null;
        long deadline=System.currentTimeMillis()+45000L;
        for(Sources.Candidate candidate:candidates.subList(0,Math.min(4,candidates.size()))){String hash="";
            try{
                if(System.currentTimeMillis()>=deadline)break;
                JSONObject added=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","add").put("link",candidate.link).put("save_to_db",false));hash=added.getString("hash");JSONArray files=added.optJSONArray("file_stats");
                for(int i=0;(files==null||files.length()==0)&&i<20&&System.currentTimeMillis()<deadline;i++){Thread.sleep(400);files=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","get").put("hash",hash)).optJSONArray("file_stats");}
                JSONObject selected=null;long size=-1;
                if(files!=null)for(int i=0;i<files.length();i++){
                    JSONObject file=files.getJSONObject(i);String path=file.optString("path");if(!Logic.isVideo(path)||path.toLowerCase(Locale.ROOT).contains("sample"))continue;
                    if(parts[3].isEmpty()){if(file.optLong("length")>size){size=file.optLong("length");selected=file;}}
                    else if(parts[3].equals(Logic.episodeKey(path))||(!candidate.episodeFile(parts[3]).isEmpty()&&path.endsWith(candidate.episodeFile(parts[3])))){selected=file;break;}
                }
                if(selected==null)throw new Exception("Episode not found");
                String streamUrl=Net.ENGINE+"/stream/video?link="+Net.enc(hash)+"&index="+selected.getInt("id")+"&play";
                int remaining=(int)Math.min(8000L,deadline-System.currentTimeMillis());
                if(remaining<500)throw new Exception("Video preparation timed out");
                Net.verifyStreamBytes(streamUrl,remaining);
                Sources.recordOutcome(candidate,true);
                return new Reply("",streamUrl);
            }catch(Exception error){failure=error;Sources.recordOutcome(candidate,false);if(!hash.isEmpty())try{Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","rem").put("hash",hash));}catch(Exception ignored){}}
        }
        throw new Exception("Источники не передают данные. Выберите другое качество или озвучку.",failure);
    }
}

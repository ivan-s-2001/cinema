package app.cinemahall.tv;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads our existing multi-API snapshot lazily; keeps only two full detail shards in RAM. */
final class CinemaSnapshot {
    private final Context context;
    private JSONArray lookup;
    private final Map<String,JSONObject> byId=new HashMap<>();
    private boolean checked;
    // Bounded parsed shelf cache: rapid D-pad navigation must not rescan or sort the full index.
    private final LinkedHashMap<String,String> shelfCache=new LinkedHashMap<String,String>(20,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> e){return size()>20;}
    };
    private final LinkedHashMap<String,JSONArray> shards=new LinkedHashMap<String,JSONArray>(4,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,JSONArray> e){return size()>2;}};
    CinemaSnapshot(Context context){this.context=context;}
    private JSONObject index;
    private void init(){if(checked)return;checked=true;try{index=new JSONObject(read("index.json"));}catch(Exception absent){index=null;}}
    private String read(String path) throws Exception {
        if(!path.matches("(?:index|search)\\.json|shards/(?:movie|tv)-[0-9]+\\.json"))throw new Exception("Invalid shard");
        try(InputStream in=context.getAssets().open("catalog/"+path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("Snapshot too large");out.write(buffer,0,n);}return out.toString("UTF-8");
        }
    }
    private JSONArray search() throws Exception {if(lookup==null){lookup=new JSONArray(read("search.json"));for(int i=0;i<lookup.length();i++){JSONObject entry=lookup.getJSONObject(i);byId.put(entry.getString("id"),entry);}}return lookup;}
    private JSONArray shard(String path) throws Exception {JSONArray data=shards.get(path);if(data==null){data=new JSONArray(read(path));shards.put(path,data);}return data;}
    private JSONObject raw(String id) throws Exception {
        init();if(index==null||!id.startsWith("ch:snapshot:"))return null;
        String wanted=id.substring(12);search();JSONObject entry=byId.get(wanted);
        if(entry==null)return null;
        JSONArray cards=shard(entry.getString("shard"));
        for(int i=0;i<cards.length();i++){JSONObject row=cards.getJSONObject(i);if(wanted.equals(stableId(row)))return row;}
        return null;
    }
    static String stableId(JSONObject row) throws JSONException {
        JSONObject ids=row.getJSONObject("ids");for(String source:new String[]{"tmdb","kinopoisk","imdb","myAnimeList","shikimori"})if(!ids.optString(source).isEmpty())return row.getString("type")+":"+source+":"+ids.getString(source)+(row.optString("collisionSuffix").isEmpty()?"":":"+row.optString("collisionSuffix"));throw new JSONException("No id");
    }
    synchronized JSONObject catalog(String type,String category,String query,int skip) throws Exception {
        init();if(index==null)return null;
        String cacheKey=type+"|"+category+"|"+query+"|"+skip;
        String cached=shelfCache.get(cacheKey);
        if(cached!=null)return new JSONObject(cached);
        JSONArray all=search(),result=new JSONArray();String needle=words(query);ArrayList<JSONObject> matches=new ArrayList<>();
        for(int i=0;i<all.length();i++){
            JSONObject entry=all.getJSONObject(i);if(!entry.optString("type").equals(type.equals("series")?"tv":"movie"))continue;
            if(!needle.isEmpty()&&!words(entry.optString("title")+" "+entry.optJSONArray("aliases")).contains(needle))continue;
            // Shards are needed for genre-specific lists; do not mislabel the same shelf as another category.
            if(category.equals("animation")||category.equals("scifi")){
                String genres=words(entry.optString("genres"));
                if(!genres.contains(category.equals("animation")?"мульт":"фантаст"))continue;
            }
            matches.add(entry);
        }
        if(category.startsWith("new"))matches.sort((a,b)->Integer.compare(b.optInt("year"),a.optInt("year")));
        // Top shelves use the snapshot's real ratings rather than a relabelled popularity list.
        if(category.startsWith("top")){
            Map<String,Double> ratings=new HashMap<>();for(JSONObject e:matches){ratings.put(e.getString("id"),ratingValue(e.opt("rating")));}
            matches.sort((a,b)->Double.compare(ratings.get(b.optString("id")),ratings.get(a.optString("id"))));
        }
        for(int i=skip;i<Math.min(matches.size(),skip+24);i++)result.put(compact(matches.get(i)));
        JSONObject response=new JSONObject().put("metas",result).put("hasMore",skip+24<matches.size());
        shelfCache.put(cacheKey,response.toString());
        return response;
    }
    private static double ratingValue(Object value){if(value instanceof Number)return ((Number)value).doubleValue();if(value instanceof JSONObject)return ((JSONObject)value).optDouble("value",((JSONObject)value).optDouble("rating",0));return 0;}
    private static double rating(JSONObject scores){if(scores==null)return 0;for(String key:new String[]{"kinopoisk","imdb","tmdb"}){Object v=scores.opt(key);if(v instanceof Number)return ((Number)v).doubleValue();if(v instanceof JSONObject){double value=((JSONObject)v).optDouble("value",0);if(value>0)return value;}}return 0;}
    private static String words(String value){return value.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private JSONObject compact(JSONObject entry) throws Exception {
        JSONObject result=new JSONObject().put("id","ch:snapshot:"+entry.getString("id")).put("type",entry.optString("type").equals("tv")?"series":"movie").put("name",entry.optString("title")).put("poster",entry.optString("poster")).put("releaseInfo",entry.optString("year"));
        // New snapshots embed verified wide artwork in the lightweight search index.
        String background=entry.optString("background");
        if(background.startsWith("https://")&&!background.equals(result.optString("poster")))result.put("background",background);
        return result;
    }
    synchronized JSONObject detail(String id) throws Exception {
        JSONObject row=raw(id);if(row==null)return null;JSONObject images=row.optJSONObject("images");
        JSONObject result=new JSONObject().put("id",id).put("type",row.optString("type").equals("tv")?"series":"movie").put("name",row.optString("title"))
            .put("poster",images==null?"":images.optString("poster")).put("description",row.optString("description")).put("releaseInfo",row.optString("year")).put("genres",row.optJSONArray("genres"));
        if(images!=null&&images.optBoolean("backdropVerified")&&!images.optString("backdrop").equals(result.optString("poster")))result.put("background",images.optString("backdrop"));
        if(result.optString("background").isEmpty()){
            JSONObject fallback=Metadata.cachedArtwork(context,document(id));
            String backdrop=fallback.optString("background");
            if(!backdrop.isEmpty()&&!backdrop.equals(result.optString("poster")))result.put("background",backdrop);
            if(result.optString("poster").isEmpty()&&!fallback.optString("poster").isEmpty())result.put("poster",fallback.getString("poster"));
        }
        if(images!=null&&!images.optString("logo").isEmpty())result.put("logo",images.optString("logo"));
        if(row.optInt("runtimeMinutes")>0)result.put("runtime",row.getInt("runtimeMinutes")+" мин");
        JSONObject scores=row.optJSONObject("ratings");if(scores!=null){
            Object imdb=scores.opt("imdb");double value=imdb instanceof Number?((Number)imdb).doubleValue():imdb instanceof JSONObject?((JSONObject)imdb).optDouble("value",((JSONObject)imdb).optDouble("rating",0)):0;
            if(value>0&&value<=10)result.put("imdbRating",String.format(Locale.ROOT,"%.1f",value));
        }
        JSONObject details=row.optJSONObject("details");JSONArray seasons=details==null?null:details.optJSONArray("seasons"),videos=new JSONArray();
        if(seasons!=null)for(int s=0;s<seasons.length();s++){
            JSONObject season=seasons.getJSONObject(s);int sn=season.optInt("number");JSONArray episodes=season.optJSONArray("episodes");if(episodes==null)continue;
            for(int e=0;e<episodes.length();e++){JSONObject ep=episodes.getJSONObject(e);int en=ep.optInt("number");if(sn<1||en<1)continue;videos.put(new JSONObject().put("id",id+":"+sn+":"+en).put("season",sn).put("episode",en).put("title",ep.optString("name")).put("overview",ep.optString("overview")).put("thumbnail",ep.optString("still")));}
        }
        result.put("videos",videos);return result;
    }
    synchronized JSONObject document(String id) throws Exception {
        JSONObject row=raw(id);if(row==null)return null;JSONObject ids=row.getJSONObject("ids");JSONArray aliases=row.optJSONArray("aliases");
        String original=row.optString("title");if(aliases!=null&&aliases.length()>1)original=aliases.optString(1);
        return new JSONObject().put("serial",row.optString("type").equals("tv")).put("id",ids.optString("kinopoisk")).put("imdb",ids.optString("imdb")).put("name_rus",row.optString("title")).put("name_original",original).put("year",row.optInt("year"));
    }
}

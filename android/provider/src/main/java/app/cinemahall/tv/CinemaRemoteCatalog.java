package app.cinemahall.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;

/** Thin server catalogue adapter for the existing Nuvio add-on contract. */
final class CinemaRemoteCatalog {
    private static final String PREFIX="ch:";
    private final LinkedHashMap<String,JSONObject> rawDetails=new LinkedHashMap<String,JSONObject>(24,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,JSONObject> entry){return size()>24;}
    };
    private final LinkedHashMap<String,JSONObject> details=new LinkedHashMap<String,JSONObject>(24,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,JSONObject> entry){return size()>24;}
    };
    private final LinkedHashMap<String,String> pages=new LinkedHashMap<String,String>(16,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,String> entry){return size()>16;}
    };
    private long checkedAt;
    private boolean enabled;
    private synchronized boolean active(){
        if(checkedAt==0 || System.currentTimeMillis()-checkedAt>10*60_000L){
            try {JSONObject config=CinemaRemoteSources.fetch("/config");enabled=config.optInt("apiVersion")==2&&config.optJSONObject("capabilities").optBoolean("home",false);}
            catch(Exception unavailable){enabled=false;}
            checkedAt=System.currentTimeMillis();
        }
        return enabled;
    }
    private static JSONObject copy(JSONObject value){try{return new JSONObject(value.toString());}catch(Exception invalid){return null;}}
    static boolean validId(String id){return id!=null&&id.matches("ch:[A-Za-z0-9:_-]{1,157}");}
    synchronized JSONObject catalog(String type,String rowId,String search,int skip){
        if(!active()||!rowId.equals(type.equals("series")?"series":"movies")||skip<0||skip>240||skip%24!=0)return null;
        String query=search==null?"":search.trim();if(query.length()>120)return null;
        String key=type+"|"+rowId+"|"+query+"|"+skip;
        if(pages.containsKey(key))try{return new JSONObject(pages.get(key));}catch(Exception invalid){pages.remove(key);return null;}
        try{
            String cursor="",path;
            for(int index=0;index<=skip/24;index++){
                if(query.isEmpty())path="/rows/"+rowId+"/items?limit=24";
                else path="/search?type="+type+"&q="+Net.enc(query)+"&limit=24";
                if(!cursor.isEmpty())path+="&cursor="+Net.enc(cursor);
                JSONObject response=CinemaRemoteSources.fetch(path);
                if(index<skip/24){cursor=response.optString("nextCursor");if(cursor.isEmpty())return new JSONObject().put("metas",new JSONArray()).put("hasMore",false);continue;}
                JSONArray items=response.getJSONArray("items"),result=new JSONArray();
                for(int i=0;i<items.length();i++){
                    JSONObject card=items.getJSONObject(i);
                    if(validId(card.optString("id")))result.put(compact(card));
                }
                JSONObject resultPage=new JSONObject().put("metas",result).put("hasMore",!response.isNull("nextCursor"));
                pages.put(key,resultPage.toString());return resultPage;
            }
        }catch(Exception unavailable){return null;}
        return null;
    }
    private static JSONObject compact(JSONObject row) throws Exception {
        JSONObject result=new JSONObject().put("id",row.getString("id")).put("type",row.getString("type"))
            .put("name",row.optString("title")).put("poster",row.optString("poster"))
            .put("releaseInfo",row.isNull("year")?"Год не указан":row.optString("year"));
        if(!row.isNull("backdrop"))result.put("background",row.optString("backdrop"));
        return result;
    }
    synchronized JSONObject detail(String id){
        if(!validId(id))return null;
        JSONObject cached=details.get(id);if(cached!=null)return copy(cached);
        if(!active())return null;
        try{
            JSONObject row=CinemaRemoteSources.fetch("/titles/"+id).getJSONObject("title");
            JSONObject result=compact(row).put("description",row.optString("description"));
            for(String field:new String[]{"genres","aliases"})if(row.optJSONArray(field)!=null)result.put(field,row.getJSONArray(field));
            if(!row.isNull("runtimeMinutes"))result.put("runtime",row.optString("runtimeMinutes"));
            if(!row.isNull("releaseDate"))result.put("released",row.optString("releaseDate")+"T00:00:00.000Z");
            JSONObject ids=row.optJSONObject("externalIds");
            if(ids!=null&&ids.has("imdb"))result.put("imdb_id",ids.getString("imdb"));
            if(!row.isNull("ageRating"))result.put("app_extras",new JSONObject().put("certificationLocal",row.optString("ageRating")));
            if("series".equals(row.optString("type"))){
                JSONArray videos=new JSONArray(),seasons=CinemaRemoteSources.fetch("/titles/"+id+"/seasons").getJSONArray("items");
                for(int i=0;i<seasons.length()&&i<30;i++){
                    int season=seasons.getJSONObject(i).optInt("number");if(season==0)continue;
                    int after=0;
                    for(int page=0;page<20;page++){
                        JSONObject answer=CinemaRemoteSources.fetch("/seasons/"+id+":s"+season+"/episodes?limit=100"+(after>0?"&after="+after:""));
                        JSONArray episodes=answer.getJSONArray("items");
                        for(int j=0;j<episodes.length();j++){
                            JSONObject ep=episodes.getJSONObject(j);int number=ep.optInt("number");if(number<1)continue;
                            JSONObject video=new JSONObject().put("id",id+":"+season+":"+number).put("season",season).put("episode",number).put("title",ep.optString("title")).put("overview",ep.optString("description"));
                            if(!ep.isNull("still"))video.put("thumbnail",ep.optString("still"));
                            if(!ep.isNull("airAt"))video.put("released",ep.optString("airAt")+"T00:00:00.000Z");
                            videos.put(video);
                        }
                        if(answer.isNull("nextAfter"))break;
                        after=answer.optInt("nextAfter");
                    }
                }
                result.put("videos",videos);
            }
            rawDetails.put(id,row);details.put(id,result);return new JSONObject(result.toString());
        }catch(Exception unavailable){return null;}
    }
    synchronized JSONObject document(String id){
        if(!validId(id))return null;
        try{
            JSONObject row=rawDetails.get(id);
            if(row==null){if(!active())return null;row=CinemaRemoteSources.fetch("/titles/"+id).getJSONObject("title");rawDetails.put(id,row);}
            JSONObject ids=row.optJSONObject("externalIds"),languages=row.optJSONObject("titlesByLanguage");
            return new JSONObject().put("serial","series".equals(row.optString("type")))
                .put("id",ids==null?"":ids.optString("kinopoisk"))
                .put("imdb",ids==null?"":ids.optString("imdb"))
                .put("name_rus",languages==null?row.optString("title"):languages.optString("ru",row.optString("title")))
                .put("name_eng",languages==null?"":languages.optString("en"))
                .put("name_original",row.optString("originalTitle",row.optString("title")))
                .put("year",row.optInt("year"));
        }catch(Exception unavailable){return null;}
    }
}

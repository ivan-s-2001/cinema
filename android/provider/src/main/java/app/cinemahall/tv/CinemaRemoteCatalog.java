package app.cinemahall.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin server catalogue adapter for the existing Nuvio add-on contract. */
final class CinemaRemoteCatalog {
    private static final String PREFIX="ch:";
    private final LinkedHashMap<String,JSONObject> details=new LinkedHashMap<String,JSONObject>(24,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,JSONObject> entry){return size()>24;}
    };
    private static final class Page {
        final String json,nextCursor;final long expiresAt;
        Page(String json,String nextCursor,long expiresAt){this.json=json;this.nextCursor=nextCursor;this.expiresAt=expiresAt;}
    }
    private final Map<String,Page> pages=Collections.synchronizedMap(new LinkedHashMap<String,Page>(16,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Page> entry){return size()>16;}
    });
    private final Map<String,String> cursors=Collections.synchronizedMap(new LinkedHashMap<String,String>(32,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,String> entry){return size()>256;}
    });
    private final LinkedHashMap<String,Long> detailLoaded=new LinkedHashMap<String,Long>(24,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Long> entry){return size()>24;}
    };
    private long checkedAt;
    private boolean enabled;
    private synchronized boolean active(){
        if(checkedAt==0 || System.currentTimeMillis()-checkedAt>(enabled?10*60_000L:15000L)){
            try {JSONObject config=CinemaRemoteSources.fetch("/config");enabled=config.optInt("apiVersion")==2&&config.optJSONObject("capabilities").optBoolean("home",false);}
            catch(Exception unavailable){enabled=false;}
            checkedAt=System.currentTimeMillis();
        }
        return enabled;
    }
    private static JSONObject copy(JSONObject value){try{return new JSONObject(value.toString());}catch(Exception invalid){return null;}}
    static boolean validId(String id){return id!=null&&id.matches("ch:[A-Za-z0-9:_-]{1,157}");}
    JSONObject catalog(String type,String rowId,String search,int skip){
        boolean own=false;
        for(String[] row:CinemaCatalog.ROWS)if(row[0].equals(rowId)&&row[2].equals(type))own=true;
        if(!own||skip<0||skip>100000||skip%24!=0)return null;
        String query=search==null?"":search.trim();if(query.length()>120)return null;
        String key=type+"|"+rowId+"|"+query+"|"+skip;
        Page cached=pages.get(key);
        if(cached!=null&&cached.expiresAt>System.currentTimeMillis())try{return new JSONObject(cached.json);}catch(Exception invalid){pages.remove(key);}
        if(!active())return fromCache(cached);
        try{
            String cursor="",path;
            int start=skip/24;
            while(start>0){
                String saved=cursors.get(type+"|"+rowId+"|"+query+"|"+(start*24));
                if(saved!=null){cursor=saved;break;}
                start--;
            }
            for(int index=start;index<=skip/24;index++){
                String pageKey=type+"|"+rowId+"|"+query+"|"+(index*24);
                Page previous=pages.get(pageKey);
                if(previous!=null&&previous.expiresAt>System.currentTimeMillis()){
                    if(index==skip/24)return new JSONObject(previous.json);
                    cursor=previous.nextCursor;
                    if(cursor.isEmpty())return new JSONObject().put("metas",new JSONArray()).put("hasMore",false);
                    continue;
                }
                if(query.isEmpty())path="/rows/"+rowId+"/items?limit=24";
                else path="/search?type="+type+"&q="+Net.enc(query)+"&limit=24";
                if(!cursor.isEmpty())path+="&cursor="+Net.enc(cursor);
                JSONObject response=CinemaRemoteSources.fetch(path);
                JSONArray items=response.getJSONArray("items"),result=new JSONArray();
                for(int i=0;i<items.length();i++){
                    JSONObject card=items.getJSONObject(i);
                    if(validId(card.optString("id")))result.put(compact(card));
                }
                cursor=response.isNull("nextCursor")?"":response.optString("nextCursor");
                if(!cursor.isEmpty())cursors.put(type+"|"+rowId+"|"+query+"|"+((index+1)*24),cursor);
                JSONObject resultPage=new JSONObject().put("metas",result).put("hasMore",!cursor.isEmpty());
                pages.put(pageKey,new Page(resultPage.toString(),cursor,System.currentTimeMillis()+120000L));
                if(index==skip/24)return resultPage;
                if(cursor.isEmpty())return new JSONObject().put("metas",new JSONArray()).put("hasMore",false);
            }
        }catch(Exception unavailable){return fromCache(cached);}
        return fromCache(cached);
    }
    private static JSONObject fromCache(Page page){try{return page==null?null:new JSONObject(page.json);}catch(Exception invalid){return null;}}
    static JSONObject compact(JSONObject row) throws Exception {
        JSONObject result=new JSONObject().put("id",row.getString("id")).put("type",row.getString("type"))
            .put("name",row.optString("title")).put("poster",row.optString("poster"))
            .put("releaseInfo",row.isNull("year")?"Год не указан":row.optString("year"));
        if(!row.isNull("backdrop"))result.put("background",row.optString("backdrop"));
        if(!row.isNull("description"))result.put("description",row.optString("description"));
        if(row.optJSONArray("genres")!=null)result.put("genres",row.getJSONArray("genres"));
        if(!row.isNull("runtimeMinutes"))result.put("runtime",row.optString("runtimeMinutes"));
        if(!row.isNull("ageRating"))result.put("app_extras",new JSONObject().put("certificationLocal",row.optString("ageRating")));
        JSONArray ratings=row.optJSONArray("ratings");JSONObject scores=new JSONObject();
        if(ratings!=null)for(int i=0;i<ratings.length();i++){JSONObject rating=ratings.optJSONObject(i);if(rating!=null)scores.put(rating.optString("source"),rating.opt("value"));}
        CinemaRatings.append(result,scores);
        return result;
    }
    synchronized JSONObject detail(String id){
        if(!validId(id))return null;
        JSONObject cached=details.get(id);
        Long loaded=detailLoaded.get(id);
        if(cached!=null&&loaded!=null&&System.currentTimeMillis()-loaded<5*60_000L)return copy(cached);
        if(!active())return cached==null?null:copy(cached);
        try{
            JSONObject ready=CinemaRemoteSources.fetch("/titles/"+id+"/tv-meta");
            JSONObject row=ready.getJSONObject("title"),meta=ready.getJSONObject("meta");
            if(!id.equals(row.optString("id"))||!id.equals(meta.optString("id")))throw new Exception("Metadata identity mismatch");
            details.put(id,meta);detailLoaded.put(id,System.currentTimeMillis());
            return copy(meta);
        }catch(Exception unavailable){return cached==null?null:copy(cached);}
    }
}

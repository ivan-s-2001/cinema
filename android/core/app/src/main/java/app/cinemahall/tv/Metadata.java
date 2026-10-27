package app.cinemahall.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Optional enrichment: failures never block the catalogue or playback. */
final class Metadata {
    static final String CINEMETA="https://v3-cinemeta.strem.io";
    static final String TVMAZE="https://api.tvmaze.com";
    static JSONArray array(String url) throws Exception {
        return new JSONArray(new String(Net.request(url,null,8*1024*1024),StandardCharsets.UTF_8));
    }
    static JSONObject load(Context context,JSONObject film,boolean details){
        SharedPreferences cache=context.getSharedPreferences("metadata",0);
        String key=film.optString("id")+(details?":detail":":art");
        JSONObject result=new JSONObject();
        try{
            String stored=cache.getString(key,"");
            if(!stored.isEmpty()){
                result=new JSONObject(stored);
                if(System.currentTimeMillis()-result.optLong("fetched")<86400000L)return result;
            }
            boolean serial=film.optBoolean("serial");String type=serial?"series":"movie";
            JSONObject meta=null;
            // Device-private setting; do not embed an API key in Git or the shipped APK.
            String kinoKey=context.getSharedPreferences("cinemahall_settings",0).getString("kinoapi_key","");
            if(!kinoKey.isEmpty())try{
                JSONObject titleData=KinoApi.byKinopoisk(film.optString("id"),kinoKey);
                if(titleData!=null)KinoApi.enrich(result,titleData,details);
            }catch(Exception ignored){}
            // Exact normalized title AND original release year: never take the first fuzzy hit.
            String title=film.optString("name_original");if(title.isEmpty())title=film.optString("name_rus");
            JSONArray hits=null;
            try{hits=Net.json(CINEMETA+"/catalog/"+type+"/top/search="+Net.enc(title)+".json",null).optJSONArray("metas");}catch(Exception ignored){}
            if(hits!=null)for(int i=0;i<hits.length();i++){
                JSONObject hit=hits.getJSONObject(i);
                if(Logic.sameTitle(title,film.optInt("year"),hit.optString("name"),hit.optString("releaseInfo"))){meta=hit;break;}
            }
            if(meta!=null&&meta.optString("id").matches("tt[0-9]+")){
                String imdb=meta.getString("id");
                if(result.optString("imdb").isEmpty())result.put("imdb",imdb);
                if(result.optString("background").isEmpty())result.put("background",meta.optString("background"));
                if(result.optString("poster").isEmpty())result.put("poster",meta.optString("poster"));
                if(details){
                    try{
                        JSONObject full=Net.json(CINEMETA+"/meta/"+type+"/"+imdb+".json",null).getJSONObject("meta");
                        for(String field:new String[]{"background","poster","cast","director","imdbRating"})if(full.has(field)&&!result.has(field))result.put(field,full.get(field));
                    }catch(Exception ignored){}
                }
            }
            if(serial&&details){
                try{
                    JSONObject show=null;
                    if(result.has("imdb"))show=Net.json(TVMAZE+"/lookup/shows?imdb="+result.getString("imdb"),null);
                    else{
                        JSONArray shows=array(TVMAZE+"/search/shows?q="+Net.enc(title));
                        for(int i=0;i<shows.length();i++){
                            JSONObject candidate=shows.getJSONObject(i).getJSONObject("show");
                            if(Logic.sameTitle(title,film.optInt("year"),candidate.optString("name"),candidate.optString("premiered"))){show=candidate;break;}
                        }
                    }
                    if(show!=null){
                        int id=show.getInt("id");result.put("tvmaze",show.optString("url")).put("status",show.optString("status"));
                        result.put("episodes",array(TVMAZE+"/shows/"+id+"/episodes"));
                        if(result.optString("background").isEmpty()){
                            JSONArray images=array(TVMAZE+"/shows/"+id+"/images");
                            for(int i=0;i<images.length();i++)if(images.getJSONObject(i).optString("type").equals("background")){
                                result.put("background",images.getJSONObject(i).getJSONObject("resolutions").getJSONObject("original").getString("url"));break;
                            }
                        }
                    }
                }catch(Exception ignored){}
            }
            // A short negative cache prevents repeated requests for unmatched titles.
            result.put("fetched",System.currentTimeMillis()-(result.has("imdb")||result.has("tvmaze")?0:82800000L));
            cache.edit().putString(key,result.toString()).apply();
        }catch(Exception ignored){}
        return result;
    }
}

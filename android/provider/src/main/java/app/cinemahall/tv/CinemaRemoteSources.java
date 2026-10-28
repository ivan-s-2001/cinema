package app.cinemahall.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Server searches releases; the TV engine only prepares and plays the chosen source. */
final class CinemaRemoteSources {
    private static final String BASE="https://cinemahome.duckdns.org/api/v2";
    private static long checkedAt;
    private static boolean enabled;
    private static final java.util.Map<String,CachedSources> cache=new java.util.LinkedHashMap<String,CachedSources>(8,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,CachedSources> entry){return size()>8;}
    };
    private static final class CachedSources {
        final ArrayList<Sources.Candidate> items;final long until;final boolean complete;
        CachedSources(ArrayList<Sources.Candidate> items,boolean complete){this.items=new ArrayList<>(items);this.complete=complete;until=System.currentTimeMillis()+(complete?120000L:1200L);}
    }
    private static final long CONFIG_TTL=10*60_000L;
    private CinemaRemoteSources() {}
    static JSONObject fetch(String path) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(BASE+path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);connection.setReadTimeout(path.contains("source-candidates")?20000:path.contains("tv-meta")?30000:10000);
        connection.setRequestProperty("Accept","application/json");
        try {
            if(connection.getResponseCode()!=200)throw new Exception("Source API unavailable");
            int budget=path.contains("tv-meta")?2*1024*1024:512*1024;
            if(connection.getContentLengthLong()>budget)throw new Exception("Source response oversized");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;
                while((n=in.read(block))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(out.size()+n>budget)throw new Exception("Source response oversized");out.write(block,0,n);}
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{connection.disconnect();}
    }
    static synchronized boolean complete(String titleId,String episode){CachedSources value=cache.get(titleId+"|"+episode);return value==null||value.complete;}
    static synchronized ArrayList<Sources.Candidate> load(String titleId,String episode) {
        if(!titleId.matches("ch:[A-Za-z0-9:_-]{1,157}")||!episode.matches("|S[0-9]{1,3}E[0-9]{1,4}"))return null;
        String cacheKey=titleId+"|"+episode;
        CachedSources recent=cache.get(cacheKey);
        if(recent!=null&&recent.until>System.currentTimeMillis())return new ArrayList<>(recent.items);
        if(System.currentTimeMillis()-checkedAt>(enabled?CONFIG_TTL:15000L) || checkedAt==0){
            try {enabled=fetch("/config").getJSONObject("capabilities").optBoolean("sourceDiscovery",false);}
            catch(Exception unavailable){enabled=false;}
            checkedAt=System.currentTimeMillis();
        }
        if(!enabled)return null;
        try {
            JSONObject response=fetch("/titles/"+titleId+"/source-candidates"+(episode.isEmpty()?"":"?episode="+episode));
            if(!"ready".equals(response.optString("status")))return null;
            ArrayList<Sources.Candidate> candidates=new ArrayList<>();java.util.HashSet<String> seen=new java.util.HashSet<>();
            boolean complete=response.optBoolean("discoveryComplete",true);
            for(int page=0;page<10;page++){
                JSONArray items=response.getJSONArray("candidates");
                for(int i=0;i<items.length()&&candidates.size()<600;i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item!=null&&Logic.validLink(item.optString("torrent_download_link"))){String identity=item.optString("hash");if(identity.isEmpty())identity=item.optString("torrent_download_link");if(seen.add(identity))candidates.add(new Sources.Candidate(item));}
                }
                complete=response.optBoolean("discoveryComplete",true);
                if(response.isNull("nextOffset")||!response.has("nextOffset"))break;
                response=fetch("/titles/"+titleId+"/source-candidates?"+(episode.isEmpty()?"":"episode="+episode+"&")+"after="+response.getInt("nextOffset"));
            }
            cache.put(cacheKey,new CachedSources(candidates,complete));
            return candidates;
        }catch(Exception unavailable){return null;}
    }
}

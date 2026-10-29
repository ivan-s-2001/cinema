package app.cinemahall.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Server searches releases; the TV engine only prepares and plays the chosen source. */
final class CinemaRemoteSources {
    private static final String BASE="https://cinemahome.duckdns.org/api/v2";
    private static volatile long checkedAt;
    private static volatile boolean enabled;
    private static final Map<String,CachedSources> cache=new LinkedHashMap<String,CachedSources>(8,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,CachedSources> entry){return size()>8;}
    };
    private static final Map<String,CompletableFuture<CachedSources>> pending=new HashMap<>();
    static final class CachedSources {
        final ArrayList<Sources.Candidate> items;final long created,until;final boolean complete;
        CachedSources(ArrayList<Sources.Candidate> items,boolean complete){this(items,complete,System.nanoTime());}
        CachedSources(ArrayList<Sources.Candidate> items,boolean complete,long created){this.created=created;this.items=new ArrayList<>(items);this.complete=complete;until=created+TimeUnit.MILLISECONDS.toNanos(complete?120000L:1200L);}
    }
    @FunctionalInterface interface PageFetcher {JSONObject get(String path,long deadline)throws Exception;}
    private static final long CONFIG_TTL=TimeUnit.MINUTES.toNanos(10);
    private CinemaRemoteSources() {}
    static JSONObject fetch(String path) throws Exception {
        return fetch(path,System.nanoTime()+TimeUnit.SECONDS.toNanos(path.contains("tv-meta")?30:path.contains("source-candidates")?20:10));
    }
    private static JSONObject fetch(String path,long deadline) throws Exception {
        byte[] bytes=Net.requestUntil(BASE+path,null,path.contains("tv-meta")?2*1024*1024:512*1024,"",deadline,false);
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    static synchronized boolean complete(String titleId,String episode){CachedSources value=cache.get(titleId+"|"+episode);return value==null||value.complete;}
    /** Every page shares the same deadline; a failed later page must not discard earlier results. */
    static CachedSources discover(String titleId,String episode,long deadline,PageFetcher fetcher)throws Exception {
        String path="/titles/"+titleId+"/source-candidates?"+(episode.isEmpty()?"":"episode="+episode+"&");
        ArrayList<Sources.Candidate> candidates=new ArrayList<>();Set<String> seen=new HashSet<>();
        int offset=0;boolean complete=false;
        for(int page=0;page<10;page++){
            try {
                if(System.nanoTime()>=deadline)throw new TimeoutException("Source discovery timed out");
                JSONObject response=fetcher.get(path+"after="+offset,deadline);
                if(!"ready".equals(response.optString("status")))throw new Exception("Source discovery not ready");
                JSONArray items=response.getJSONArray("candidates");
                for(int i=0;i<items.length()&&candidates.size()<600;i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item!=null&&Logic.validLink(item.optString("torrent_download_link"))){String identity=item.optString("hash");if(identity.isEmpty())identity=item.optString("torrent_download_link");if(seen.add(identity))candidates.add(new Sources.Candidate(item));}
                }
                complete=response.optBoolean("discoveryComplete",true);
                if(response.isNull("nextOffset")||!response.has("nextOffset"))break;
                complete=false;
                Object next=response.get("nextOffset");
                if(!(next instanceof Number))break;
                double number=((Number)next).doubleValue();
                if(number<=offset||number>=600||number!=Math.floor(number))break;
                offset=(int)number;
            } catch(Exception unavailable){
                if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
                if(candidates.isEmpty())throw unavailable;
                complete=false;break;
            }
        }
        return new CachedSources(candidates,complete);
    }
    static ArrayList<Sources.Candidate> load(String titleId,String episode) {
        return load(titleId,episode,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
    }
    static ArrayList<Sources.Candidate> load(String titleId,String episode,long parentDeadline) {
        if(!titleId.matches("ch:[A-Za-z0-9:_-]{1,157}")||!episode.matches("|S[0-9]{1,3}E[0-9]{1,4}"))return null;
        long deadline=Math.min(parentDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        if(deadline<=System.nanoTime())return null;
        String key=titleId+"|"+episode;CachedSources recent;CompletableFuture<CachedSources> task;boolean owner;
        synchronized(CinemaRemoteSources.class){
            recent=cache.get(key);
            if(recent!=null&&recent.until>System.nanoTime())return new ArrayList<>(recent.items);
            task=pending.get(key);owner=task==null;
            if(owner){if(pending.size()>=4)return null;task=new CompletableFuture<>();pending.put(key,task);}
        }
        CachedSources result=null;
        try {
            if(!owner){CachedSources value=task.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);return value==null?null:new ArrayList<>(value.items);}
            long now=System.nanoTime();
            if(checkedAt==0||now-checkedAt>(enabled?CONFIG_TTL:TimeUnit.SECONDS.toNanos(15))){
                try{enabled=fetch("/config",Math.min(deadline,now+TimeUnit.SECONDS.toNanos(5))).getJSONObject("capabilities").optBoolean("sourceDiscovery",false);}
                catch(Exception unavailable){enabled=false;if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}
                checkedAt=System.nanoTime();
            }
            if(!enabled)return null;
            result=discover(titleId,episode,deadline,CinemaRemoteSources::fetch);
            return new ArrayList<>(result.items);
        }catch(Exception unavailable){
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            if(recent!=null&&System.nanoTime()-recent.created<TimeUnit.MINUTES.toNanos(2)){result=new CachedSources(recent.items,false,recent.created);return new ArrayList<>(recent.items);}
            return null;
        }finally{
            if(owner){synchronized(CinemaRemoteSources.class){if(result!=null)cache.put(key,result);pending.remove(key);task.complete(result);}}
        }
    }
}

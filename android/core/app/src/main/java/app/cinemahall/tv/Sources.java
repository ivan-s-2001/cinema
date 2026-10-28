package app.cinemahall.tv;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Internal routing only: no torrent vocabulary belongs in the viewing UI. */
final class Sources {
    private static final long CACHE_MS=180_000;
    private static final LinkedHashMap<List<?>,CacheEntry> CACHE=new LinkedHashMap<List<?>,CacheEntry>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<List<?>,CacheEntry> item){return size()>16;}
    };
    private static final class CacheEntry {
        final long created; final ArrayList<Candidate> candidates;
        CacheEntry(List<Candidate> candidates,long created){this.candidates=new ArrayList<>(candidates);this.created=created;}
    }
    static synchronized ArrayList<Candidate> cached(List<?> key,long now){
        CacheEntry item=CACHE.get(key);
        if(item==null)return null;
        if(now-item.created>=CACHE_MS||now<item.created){CACHE.remove(key);return null;}
        return new ArrayList<>(item.candidates);
    }
    static synchronized void remember(List<?> key,List<Candidate> candidates,long now){
        if(!candidates.isEmpty())CACHE.put(key,new CacheEntry(candidates,now));
    }
    // Remember which distributions actually open, not just which claim many seeds.
    private static final long FAILURE_COOLDOWN_MS=5*60_000L;
    private static final LinkedHashMap<String,Long> failures=new LinkedHashMap<String,Long>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>128;}
    };
    private static final LinkedHashMap<String,Long> successes=new LinkedHashMap<String,Long>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>128;}
    };
    static synchronized void recordOutcome(Candidate c,boolean success){
        String key=c.hash.isEmpty()?c.link:c.hash;
        if(key.isEmpty())return;
        if(success){failures.remove(key);successes.put(key,System.currentTimeMillis());}
        else {failures.put(key,System.currentTimeMillis());}
    }
    private static synchronized int experienceScore(Candidate c){
        String key=c.hash.isEmpty()?c.link:c.hash;
        long now=System.currentTimeMillis();
        Long failed=failures.get(key);
        if(failed!=null&&now-failed<FAILURE_COOLDOWN_MS)return -500;
        Long passed=successes.get(key);
        return passed!=null&&now-passed<24*60*60_000L?175:0;
    }
    static final class Candidate {
        final String link,hash,quality,translation,languages,provider;
        final List<String> technologyHints;
        final int seeds;
        final JSONObject episodeFiles;
        Candidate(JSONObject value){this(value,"zona");}
        Candidate(JSONObject value,String origin){
            provider=origin;
            if("zona".equals(origin)){
                technologyHints=Logic.releaseTechnologyHints(value.optString("name",value.optString("title",value.optString("torrent_name"))));
                link=value.optString("torrent_download_link");
                String id=value.optString("hash");hash=id.isEmpty()?Logic.infoHash(link):id.toLowerCase(Locale.ROOT);
                String resolution=Logic.qualityLabel(value.optString("resolution"));
                quality="Не указано".equals(resolution)?Logic.qualityFromTitle(value.optString("name",value.optString("title",value.optString("torrent_name")))):resolution;
                translation=value.optString("translate_info").trim();languages=value.optString("languages_parser").toLowerCase(Locale.ROOT);seeds=value.optInt("seeds");
                JSONObject mapping;try{mapping=new JSONObject(value.optString("episodes_map_auto","{}"));}catch(Exception ignored){mapping=new JSONObject();}episodeFiles=mapping;
            }else{
                technologyHints=Logic.releaseTechnologyHints(value.optString("Title",value.optString("Name")));
                String magnet=value.optString("Magnet");
                link=Logic.validLink(magnet)?magnet:value.optString("Link");
                String id=value.optString("Hash");hash=id.isEmpty()?Logic.infoHash(link):id.toLowerCase(Locale.ROOT);
                quality=Logic.qualityFromTitle(value.optString("Title",value.optString("Name")));
                translation="Не указано";languages="";seeds=value.optInt("Seed");episodeFiles=new JSONObject();
            }
        }
        int score(String target){
            return Logic.sourceScore(quality,languages,translation,seeds,target);
        }
        String episodeFile(String episode){
            Iterator<String> keys=episodeFiles.keys();while(keys.hasNext()){String key=keys.next();if(episode.equals(Logic.episodeKey(key)))return episodeFiles.optString(key);}return "";
        }
    }
    static ArrayList<Candidate> load(String movieId,String episode) throws Exception {
        return load(movieId,episode,Collections.emptyList(),0);
    }
    /** Zona by precise Kinopoisk ID; optional Torznab/RuTor by Russian/English title. */
    static ArrayList<Candidate> load(String movieId,String episode,List<String> aliases,int year) throws Exception {
        boolean hasKinopoisk=movieId!=null&&movieId.matches("[0-9]+");
        if(!hasKinopoisk&&(aliases==null||aliases.isEmpty()))throw new Exception("Не найден фильм");
        List<?> cacheKey=Arrays.asList(movieId,episode,aliases==null?Collections.emptyList():new ArrayList<>(aliases),year);
        ArrayList<Candidate> previous=cached(cacheKey,System.currentTimeMillis());
        if(previous!=null)return previous;
        String exact=Logic.catalogEpisode(episode==null?"":episode);
        String fields="hash,torrent_download_link,resolution,size_bytes,translate_info,languages_parser,seeds,episodes_map_auto";
        ArrayList<Candidate> result=new ArrayList<>();HashSet<String>seen=new HashSet<>();
        Exception zonaError=null;
        try {if(hasKinopoisk){
            String query="kinopoisk_id:"+movieId+" AND playable:true AND trailer:false";
            JSONArray docs=Net.select("torrent",query+(exact.isEmpty()?"":" AND episodes:\""+exact+"\""),"seeds desc",0,200,fields).getJSONArray("docs");
            if(docs.length()==0&&!exact.isEmpty())docs=Net.select("torrent",query,"seeds desc",0,200,fields).getJSONArray("docs");
            append(result,seen,docs,"zona",Collections.emptyList());
        }}catch(Exception error){zonaError=error;}
        // Search is local to TorrServer; Jackett/Prowlarr runs on another device/server.
        try {
            JSONObject sets=Net.action("/settings","get");
            boolean torznab=sets.optBoolean("EnableTorznabSearch")
                && sets.optJSONArray("TorznabUrls")!=null && sets.optJSONArray("TorznabUrls").length()>0;
            boolean rutor=sets.optBoolean("EnableRutorSearch");
            if(torznab||rutor){
                ArrayList<String> names=aliases==null?new ArrayList<>():new ArrayList<>(aliases);
                if(names.isEmpty()&&hasKinopoisk){
                    JSONArray movies=Net.select("movie","id:"+movieId,"id asc",0,1,"name_rus,name_eng,name_original,year").getJSONArray("docs");
                    if(movies.length()>0){
                        JSONObject movie=movies.getJSONObject(0);year=movie.optInt("year",year);
                        for(String field:new String[]{"name_rus","name_original","name_eng"})names.add(movie.optString(field));
                    }
                }
                List<String> queries=Logic.torrentQueries(names,year,episode);
                int firstExtra=result.size();
                if(torznab)for(String term:queries){
                    try{append(result,seen,search("/torznab/search/",term),"torznab",names);}
                    catch(Exception ignored){} // Optional indexers must not break Zona.
                    if(result.size()>=400||result.size()-firstExtra>=80)break;
                }
                if(rutor&&!queries.isEmpty()&&result.size()<400){
                    try{append(result,seen,search("/search/",queries.get(0)),"rutor",names);}
                    catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){} // Embedded engine or extra search may be unavailable.
        if(result.isEmpty()&&zonaError!=null)throw zonaError;
        // The player immediately requests the same title again when opening a selected variant.
        remember(cacheKey,result,System.currentTimeMillis());
        return result;
    }
    private static JSONArray search(String path,String term) throws Exception {
        byte[] data=Net.request(Net.ENGINE+path+"?query="+Net.enc(term),null,2*1024*1024);
        return new JSONArray(new String(data,StandardCharsets.UTF_8));
    }
    private static void append(ArrayList<Candidate> result,HashSet<String> seen,JSONArray docs,String source,List<String> aliases){
        for(int i=0;i<docs.length()&&result.size()<450;i++){
            JSONObject json=docs.optJSONObject(i);if(json==null)continue;
            if(!"zona".equals(source)&&!Logic.torrentMatches(json.optString("Title",json.optString("Name")),aliases))continue;
            Candidate candidate=new Candidate(json,source);
            String identity=candidate.hash.isEmpty()?candidate.link:candidate.hash;
            if(Logic.validLink(candidate.link)&&seen.add(identity))result.add(candidate);
        }
    }
    static ArrayList<Candidate> ordered(List<Candidate> all,String quality,String translation,String autoTarget){
        return ordered(all,quality,translation,autoTarget,"");
    }
    static String technologyKey(Candidate candidate){
        ArrayList<String> hints=new ArrayList<>(playbackTechnologyHints(candidate));
        Collections.sort(hints);
        return hints.isEmpty()?"unspecified":String.join("+",hints);
    }
    static List<String> playbackTechnologyHints(Candidate candidate){
        ArrayList<String> result=new ArrayList<>();
        for(String hint:candidate.technologyHints)if(Arrays.asList("Dolby Vision","HDR10+","HDR","HLG","Dolby Atmos").contains(hint))result.add(hint);
        return result;
    }
    static ArrayList<Candidate> ordered(List<Candidate> all,String quality,String translation,String autoTarget,String technology){
        ArrayList<Candidate> result=new ArrayList<>();
        for(Candidate c:all)if((quality.isEmpty()||quality.equals(c.quality))&&(translation.isEmpty()||Logic.voiceKey(translation).equals(Logic.voiceKey(c.translation)))&&(technology.isEmpty()||technology.equals(technologyKey(c))))result.add(c);
        result.sort((a,b)->Integer.compare(b.score(autoTarget)+experienceScore(b),a.score(autoTarget)+experienceScore(a)));return result;
    }
    /** Automatic playback tries distinct quality/voice combinations before exhausting
     * one release family. Keeps the highest-ranked candidate first. */
    static ArrayList<Candidate> automaticFallbacks(List<Candidate> all,String target){
        ArrayList<Candidate> ranked=ordered(all,"","",target);
        LinkedHashMap<String,ArrayDeque<Candidate>> groups=new LinkedHashMap<>();
        for(Candidate c:ranked){
            String group=c.quality+"|"+Logic.voiceKey(c.translation);
            groups.computeIfAbsent(group,k->new ArrayDeque<>()).addLast(c);
        }
        ArrayList<Candidate> result=new ArrayList<>(ranked.size());
        boolean more;
        do{
            more=false;
            for(ArrayDeque<Candidate> candidates:groups.values()){
                Candidate next=candidates.pollFirst();
                if(next!=null){result.add(next);more=true;}
            }
        }while(more);
        return result;
    }
    /** One quality tab -> unique voice choices -> ordered source fallbacks. No torrent URLs in UI options. */
    static LinkedHashMap<String,LinkedHashMap<String,ArrayList<Candidate>>> grouped(List<Candidate> all,String target){
        TreeMap<String,LinkedHashMap<String,ArrayList<Candidate>>> levels=new TreeMap<>((a,b)->{
            int order=Integer.compare(Logic.qualityRank(a),Logic.qualityRank(b));
            return order!=0?order:a.compareTo(b);
        });
        for(Candidate c:all){
            String voice=Logic.voiceKey(c.translation)+"|"+technologyKey(c);
            ArrayList<Candidate> options=levels.computeIfAbsent(c.quality,q->new LinkedHashMap<>()).computeIfAbsent(voice,v->new ArrayList<>());
            boolean duplicate=false;
            for(Candidate previous:options)if(!previous.hash.isEmpty()&&previous.hash.equals(c.hash)){duplicate=true;break;}
            if(!duplicate)options.add(c);
        }
        LinkedHashMap<String,LinkedHashMap<String,ArrayList<Candidate>>> result=new LinkedHashMap<>();
        for(Map.Entry<String,LinkedHashMap<String,ArrayList<Candidate>>> e:levels.entrySet()){
            e.getValue().values().forEach(options->options.sort((a,b)->Integer.compare(b.score(target),a.score(target))));
            result.put(e.getKey(),e.getValue());
        }
        return result;
    }
}

package app.cinemahall.tv;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Internal routing only: no torrent vocabulary belongs in the viewing UI. */
final class Sources {
    static final class Candidate {
        final String link,hash,quality,translation,languages,provider;
        final int seeds;
        final JSONObject episodeFiles;
        Candidate(JSONObject value){this(value,"zona");}
        Candidate(JSONObject value,String origin){
            provider=origin;
            if("zona".equals(origin)){
                link=value.optString("torrent_download_link");
                String id=value.optString("hash");hash=id.isEmpty()?Logic.infoHash(link):id.toLowerCase(Locale.ROOT);
                quality=Logic.qualityLabel(value.optString("resolution"));
                translation=value.optString("translate_info").trim();languages=value.optString("languages_parser").toLowerCase(Locale.ROOT);seeds=value.optInt("seeds");
                JSONObject mapping;try{mapping=new JSONObject(value.optString("episodes_map_auto","{}"));}catch(Exception ignored){mapping=new JSONObject();}episodeFiles=mapping;
            }else{
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
        String exact=Logic.catalogEpisode(episode);
        String fields="hash,torrent_download_link,resolution,size_bytes,translate_info,languages_parser,seeds,episodes_map_auto";
        ArrayList<Candidate> result=new ArrayList<>();HashSet<String>seen=new HashSet<>();
        Exception zonaError=null;
        try {if(hasKinopoisk){
            String query="kinopoisk_id:"+movieId+" AND playable:true AND trailer:false";
            JSONArray docs=Net.select("torrent",query+(exact.isEmpty()?"":" AND episodes:\""+exact+"\""),"seeds desc",0,200,fields).getJSONArray("docs");
            if(docs.length()==0&&!exact.isEmpty())docs=Net.select("torrent",query,"seeds desc",0,200,fields).getJSONArray("docs");
            append(result,seen,docs,"zona");
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
                if(torznab)for(String term:queries){
                    try{append(result,seen,search("/torznab/search/",term),"torznab");}
                    catch(Exception ignored){} // Optional indexers must not break Zona.
                    if(result.size()>=250)break;
                }
                if(rutor&&!queries.isEmpty()&&result.size()<250){
                    try{append(result,seen,search("/search/",queries.get(0)),"rutor");}
                    catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){} // Embedded engine or extra search may be unavailable.
        if(result.isEmpty()&&zonaError!=null)throw zonaError;
        return result;
    }
    private static JSONArray search(String path,String term) throws Exception {
        byte[] data=Net.request(Net.ENGINE+path+"?query="+Net.enc(term),null,2*1024*1024);
        return new JSONArray(new String(data,StandardCharsets.UTF_8));
    }
    private static void append(ArrayList<Candidate> result,HashSet<String> seen,JSONArray docs,String source){
        for(int i=0;i<docs.length()&&result.size()<300;i++){
            JSONObject json=docs.optJSONObject(i);if(json==null)continue;
            Candidate candidate=new Candidate(json,source);
            String identity=candidate.hash.isEmpty()?candidate.link:candidate.hash;
            if(Logic.validLink(candidate.link)&&seen.add(identity))result.add(candidate);
        }
    }
    static ArrayList<Candidate> ordered(List<Candidate> all,String quality,String translation,String autoTarget){
        ArrayList<Candidate> result=new ArrayList<>();
        for(Candidate c:all)if((quality.isEmpty()||quality.equals(c.quality))&&(translation.isEmpty()||Logic.voiceKey(translation).equals(Logic.voiceKey(c.translation))))result.add(c);
        result.sort((a,b)->Integer.compare(b.score(autoTarget),a.score(autoTarget)));return result;
    }
    /** One quality tab -> unique voice choices -> ordered source fallbacks. No torrent URLs in UI options. */
    static LinkedHashMap<String,LinkedHashMap<String,ArrayList<Candidate>>> grouped(List<Candidate> all,String target){
        TreeMap<String,LinkedHashMap<String,ArrayList<Candidate>>> levels=new TreeMap<>((a,b)->{
            int order=Integer.compare(Logic.qualityRank(a),Logic.qualityRank(b));
            return order!=0?order:a.compareTo(b);
        });
        for(Candidate c:all){
            String voice=Logic.voiceKey(c.translation);
            ArrayList<Candidate> options=levels.computeIfAbsent(c.quality,q->new LinkedHashMap<>()).computeIfAbsent(voice,v->new ArrayList<>());
            boolean duplicate=false;
            for(Candidate previous:options)if(previous.hash.equals(c.hash)){duplicate=true;break;}
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

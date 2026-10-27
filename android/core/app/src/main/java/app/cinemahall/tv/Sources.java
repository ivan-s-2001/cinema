package app.cinemahall.tv;

import org.json.*;
import java.util.*;

/** Internal routing only: no torrent vocabulary belongs in the viewing UI. */
final class Sources {
    static final class Candidate {
        final String link,hash,quality,translation,languages;
        final int seeds;
        final JSONObject episodeFiles;
        Candidate(JSONObject value){
            link=value.optString("torrent_download_link");String id=value.optString("hash");hash=id.isEmpty()?link:id.toLowerCase(Locale.ROOT);
            quality=Logic.qualityLabel(value.optString("resolution"));translation=value.optString("translate_info").trim();languages=value.optString("languages_parser").toLowerCase(Locale.ROOT);seeds=value.optInt("seeds");
            JSONObject mapping;try{mapping=new JSONObject(value.optString("episodes_map_auto","{}"));}catch(Exception ignored){mapping=new JSONObject();}episodeFiles=mapping;
        }
        int score(String target){
            return Logic.sourceScore(quality,languages,translation,seeds,target);
        }
        String episodeFile(String episode){
            Iterator<String> keys=episodeFiles.keys();while(keys.hasNext()){String key=keys.next();if(episode.equals(Logic.episodeKey(key)))return episodeFiles.optString(key);}return "";
        }
    }
    static ArrayList<Candidate> load(String movieId,String episode) throws Exception {
        if(movieId==null||!movieId.matches("[0-9]+"))throw new Exception("Не найден фильм");
        String exact=Logic.catalogEpisode(episode);
        String query="kinopoisk_id:"+movieId+" AND playable:true AND trailer:false";
        String fields="hash,torrent_download_link,resolution,size_bytes,translate_info,languages_parser,seeds,episodes_map_auto";
        JSONArray docs=Net.select("torrent",query+(exact.isEmpty()?"":" AND episodes:\""+exact+"\""),"seeds desc",0,200,fields).getJSONArray("docs");
        if(docs.length()==0&&!exact.isEmpty())docs=Net.select("torrent",query,"seeds desc",0,200,fields).getJSONArray("docs");
        ArrayList<Candidate> result=new ArrayList<>();HashSet<String>seen=new HashSet<>();
        for(int i=0;i<docs.length();i++){
            Candidate candidate=new Candidate(docs.getJSONObject(i));if(Logic.validLink(candidate.link)&&seen.add(candidate.hash))result.add(candidate);
        }
        return result;
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

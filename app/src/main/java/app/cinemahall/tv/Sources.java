package app.cinemahall.tv;

import org.json.*;
import java.util.*;

/** Internal routing only: no torrent vocabulary belongs in the viewing UI. */
final class Sources {
    static final class Candidate {
        final String link,hash,quality,translation,languages;
        final int seeds;
        Candidate(JSONObject value){
            link=value.optString("torrent_download_link");String id=value.optString("hash");hash=id.isEmpty()?link:id.toLowerCase(Locale.ROOT);
            quality=Logic.qualityLabel(value.optString("resolution"));translation=value.optString("translate_info").trim();languages=value.optString("languages_parser").toLowerCase(Locale.ROOT);seeds=value.optInt("seeds");
        }
        int score(String target){
            return Logic.sourceScore(quality,languages,translation,seeds,target);
        }
    }
    static ArrayList<Candidate> load(String movieId) throws Exception {
        if(movieId==null||!movieId.matches("[0-9]+"))throw new Exception("Не найден фильм");
        JSONArray docs=Net.select("torrent","kinopoisk_id:"+movieId+" AND playable:true AND trailer:false","seeds desc",0,200,"hash,torrent_download_link,resolution,size_bytes,translate_info,languages_parser,seeds").getJSONArray("docs");
        ArrayList<Candidate> result=new ArrayList<>();HashSet<String>seen=new HashSet<>();
        for(int i=0;i<docs.length();i++){
            Candidate candidate=new Candidate(docs.getJSONObject(i));if(Logic.validLink(candidate.link)&&seen.add(candidate.hash))result.add(candidate);
        }
        return result;
    }
    static ArrayList<Candidate> ordered(List<Candidate> all,String quality,String translation,String autoTarget){
        ArrayList<Candidate> result=new ArrayList<>();
        for(Candidate c:all)if((quality.isEmpty()||quality.equals(c.quality))&&(translation.isEmpty()||translation.equals(c.translation)))result.add(c);
        result.sort((a,b)->Integer.compare(b.score(autoTarget),a.score(autoTarget)));return result;
    }
}

package app.cinemahall.tv;

import org.json.*;
import java.util.*;

/** Internal routing only: no torrent vocabulary belongs in the viewing UI. */
final class Sources {
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
        final String link,hash,quality,translation,languages;
        final List<String> technologyHints;
        final int seeds;
        final JSONObject episodeFiles;
        Candidate(JSONObject value){
            technologyHints=Logic.releaseTechnologyHints(value.optString("name",value.optString("title",value.optString("torrent_name"))));
            link=value.optString("torrent_download_link");
            String id=value.optString("hash");hash=id.isEmpty()?Logic.infoHash(link):id.toLowerCase(Locale.ROOT);
            String resolution=Logic.qualityLabel(value.optString("resolution"));
            quality="Не указано".equals(resolution)?Logic.qualityFromTitle(value.optString("name",value.optString("title",value.optString("torrent_name")))):resolution;
            translation=value.optString("translate_info").trim();languages=value.optString("languages_parser").toLowerCase(Locale.ROOT);seeds=value.optInt("seeds");
            JSONObject mapping;try{mapping=new JSONObject(value.optString("episodes_map_auto","{}"));}catch(Exception ignored){mapping=new JSONObject();}episodeFiles=mapping;
        }
        int score(String target){
            return Logic.sourceScore(quality,languages,translation,seeds,target);
        }
        String episodeFile(String episode){
            Iterator<String> keys=episodeFiles.keys();while(keys.hasNext()){String key=keys.next();if(episode.equals(Logic.episodeKey(key)))return episodeFiles.optString(key);}return "";
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

package app.cinemahall.tv;

import org.json.*;
import java.util.*;

/** One metadata response owns its playback URLs, so Range reopens keep the same source. */
final class CinemaPlaybackSession {
    private static final class Choice {
        String location;
        Sources.Candidate candidate;
        int recovery;
        String quality,voice,technology;
        final Set<String> rejected=new HashSet<>();
    }
    private final Map<String,Choice> choices=new HashMap<>();
    private Choice choice(String key){return choices.computeIfAbsent(key,ignored->new Choice());}
    private static String identity(Sources.Candidate candidate){return candidate.hash.isEmpty()?candidate.link:candidate.hash;}
    String location(String key){return choice(key).location;}
    void prepared(String key,Sources.Candidate candidate,String location){Choice value=choice(key);value.candidate=candidate;value.location=location;
        if(value.quality==null){value.quality=candidate.quality;value.voice=Logic.voiceKey(candidate.translation);value.technology=Sources.technologyKey(candidate);}}
    void reject(String key,Sources.Candidate candidate){choice(key).rejected.add(identity(candidate));}
    boolean accepts(String key,Sources.Candidate candidate){Choice value=choice(key);return !value.rejected.contains(identity(candidate)) && (value.quality==null ||
        value.quality.equals(candidate.quality)&&value.voice.equals(Logic.voiceKey(candidate.translation))&&value.technology.equals(Sources.technologyKey(candidate)));}
    void recover(String key,int request)throws Exception {
        Choice value=choice(key);
        if(request<0||request>2||request<value.recovery||request>value.recovery+1)throw new Exception("Invalid source recovery");
        if(request==value.recovery)return; // Range opens of the same retry URL are idempotent.
        if(value.candidate==null||value.location==null)throw new Exception("No prepared source to recover");
        value.rejected.add(identity(value.candidate));value.candidate=null;value.location=null;value.recovery=request;
    }

    static JSONObject selectFile(JSONArray files,String episode,Sources.Candidate candidate)throws Exception {
        if(files==null)return null;
        ArrayList<JSONObject> videos=new ArrayList<>();JSONObject largest=null;long size=-1;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.optJSONObject(i);if(file==null)continue;
            String path=file.optString("path");
            if(!Logic.isVideo(path)||path.toLowerCase(Locale.ROOT).contains("sample"))continue;
            videos.add(file);
            if(file.optLong("length")>size){size=file.optLong("length");largest=file;}
        }
        if(episode.isEmpty())return largest;
        String mapped=candidate.episodeFile(episode).replace('\\','/');JSONObject selected=null;
        if(!mapped.isEmpty()){
            for(JSONObject file:videos){String path=file.optString("path").replace('\\','/');
                if(path.equals(mapped)||path.endsWith("/"+mapped)){
                    if(selected!=null)return null;
                    String named=Logic.episodeKey(path);
                    if(!named.isEmpty()&&!episode.equals(named))return null;
                    selected=file;
                }
            }
            if(selected!=null)return selected;
        }
        for(JSONObject file:videos)if(episode.equals(Logic.episodeKey(file.optString("path")))){
            if(selected!=null)return null;
            selected=file;
        }
        return selected;
    }
}

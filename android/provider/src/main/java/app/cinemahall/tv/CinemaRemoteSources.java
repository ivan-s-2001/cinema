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
    private static final long CONFIG_TTL=10*60_000L;
    private CinemaRemoteSources() {}
    static JSONObject fetch(String path) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(BASE+path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);connection.setReadTimeout(path.contains("source-candidates")?20000:10000);
        connection.setRequestProperty("Accept","application/json");
        try {
            if(connection.getResponseCode()!=200)throw new Exception("Source API unavailable");
            if(connection.getContentLengthLong()>512*1024)throw new Exception("Source response oversized");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;
                while((n=in.read(block))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(out.size()+n>512*1024)throw new Exception("Source response oversized");out.write(block,0,n);}
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{connection.disconnect();}
    }
    static synchronized ArrayList<Sources.Candidate> load(String titleId,String episode) {
        if(!titleId.matches("ch:[A-Za-z0-9:_-]{1,157}")||!episode.matches("|S[0-9]{1,3}E[0-9]{1,4}"))return null;
        if(System.currentTimeMillis()-checkedAt>(enabled?CONFIG_TTL:15000L) || checkedAt==0){
            try {enabled=fetch("/config").getJSONObject("capabilities").optBoolean("sourceDiscovery",false);}
            catch(Exception unavailable){enabled=false;}
            checkedAt=System.currentTimeMillis();
        }
        if(!enabled)return null;
        try {
            JSONObject response=fetch("/titles/"+titleId+"/source-candidates"+(episode.isEmpty()?"":"?episode="+episode));
            if(!"ready".equals(response.optString("status")))return null;
            JSONArray items=response.getJSONArray("candidates");
            ArrayList<Sources.Candidate> candidates=new ArrayList<>();
            for(int i=0;i<items.length()&&i<60;i++){
                JSONObject item=items.optJSONObject(i);
                if(item!=null&&Logic.validLink(item.optString("torrent_download_link")))candidates.add(new Sources.Candidate(item));
            }
            return candidates;
        }catch(Exception unavailable){return null;}
    }
}

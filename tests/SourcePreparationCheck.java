package app.cinemahall.tv;

import org.json.*;
import java.util.concurrent.TimeUnit;

/** Offline regressions for paging, file choice and idempotent source recovery. */
public final class SourcePreparationCheck {
    private static final String MAGNET="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private static Sources.Candidate source(String hash,String quality,String voice,String name)throws Exception {
        return new Sources.Candidate(new JSONObject().put("torrent_download_link",MAGNET).put("hash",hash).put("resolution",quality).put("translate_info",voice).put("name",name));
    }
    private static JSONObject file(int id,String path,long length)throws Exception{return new JSONObject().put("id",id).put("path",path).put("length",length);}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int[] calls={0};
        JSONObject item=new JSONObject().put("torrent_download_link",MAGNET).put("hash","abc");
        CinemaRemoteSources.CachedSources partial=CinemaRemoteSources.discover("ch:361","S1E1",deadline,(path,passed)->{
            check(passed==deadline,"Each page must share the original deadline");
            check(path.contains("episode=S1E1"),"Episode must survive paging");
            if(calls[0]++==0)return new JSONObject().put("status","ready").put("candidates",new JSONArray().put(item)).put("nextOffset",50).put("discoveryComplete",true);
            check(path.endsWith("after=50"),"Server pagination cursor");throw new java.io.IOException("later page unavailable");
        });
        check(calls[0]==2&&partial.items.size()==1&&!partial.complete,"Keep usable earlier pages after a later error");
        for(Object cursor:new Object[]{0,-1,600,1.5,"50"}){
            calls[0]=0;
            CinemaRemoteSources.CachedSources invalid=CinemaRemoteSources.discover("ch:361","",deadline,(path,passed)->{
                calls[0]++;return new JSONObject().put("status","ready").put("candidates",new JSONArray().put(item).put(item)).put("nextOffset",cursor);
            });
            check(calls[0]==1&&invalid.items.size()==1&&!invalid.complete,"Reject invalid cursors and deduplicate sources");
        }
        calls[0]=0;
        try{CinemaRemoteSources.discover("ch:361","",System.nanoTime()-1,(path,passed)->{calls[0]++;return new JSONObject();});throw new AssertionError("Expired deadline accepted");}catch(java.util.concurrent.TimeoutException expected){}
        check(calls[0]==0,"Expired discovery must not start another request");
        calls[0]=0;
        CinemaRemoteSources.CachedSources completed=CinemaRemoteSources.discover("ch:361","",deadline,(path,passed)->{
            int page=calls[0]++;JSONObject response=new JSONObject().put("status","ready").put("discoveryComplete",true).put("candidates",new JSONArray().put(item));
            return page==0?response.put("nextOffset",50):response;
        });
        check(calls[0]==2&&completed.complete&&completed.items.size()==1,"Exhausted pages can be complete");
        Sources.Candidate first=source("first","1080p","LostFilm","Film 1080p HDR");
        Sources.Candidate second=source("second","1080p","Lost Film","Film 1080p HDR");
        CinemaPlaybackSession session=new CinemaPlaybackSession();String key="1080p/lostfilm/HDR";
        session.recover(key,0);session.prepared(key,first,"http://local/first");
        session.recover(key,0);check(session.location(key).equals("http://local/first"),"Range reopen must reuse the pinned source");
        try{session.recover(key,2);throw new AssertionError("Recovery sequence skipped");}catch(Exception expected){}
        session.recover(key,1);session.recover(key,1);
        check(session.location(key)==null&&!session.accepts(key,first)&&session.accepts(key,second),"Reject dead source once, allow equivalent studio aliases");
        check(!session.accepts(key,source("third","720p","LostFilm","HDR")),"Do not change quality");
        check(!session.accepts(key,source("third","1080p","Jaskier","HDR")),"Do not change voice");
        check(!session.accepts(key,source("third","1080p","LostFilm","SDR")),"Do not change technology");
        session.prepared(key,second,"http://local/second");session.recover(key,1);
        check(session.location(key).equals("http://local/second"),"Repeated retry URL must not consume another source");
        session.recover(key,2);check(!session.accepts(key,second),"Second failed source excluded");
        try{session.recover(key,3);throw new AssertionError("Recovery budget exceeded");}catch(Exception expected){}
        try{session.recover(key,0);throw new AssertionError("Old URL reused after rotation");}catch(Exception expected){}
        check(session.accepts("other-choice",first),"Failures must stay within their playback choice");
        JSONArray files=new JSONArray().put(file(1,"Pack/S01E01.mkv",10)).put(file(2,"Pack/S01E02.mkv",100)).put(file(3,"sample.mkv",1000));
        check(CinemaPlaybackSession.selectFile(files,"S1E1",first).getInt("id")==1,"Exact episode name");
        check(CinemaPlaybackSession.selectFile(files,"",first).getInt("id")==2,"Largest movie excluding sample");
        check(CinemaPlaybackSession.selectFile(new JSONArray().put(file(1,"a/S01E01.mkv",10)).put(file(2,"b/S01E01.mkv",20)),"S1E1",first)==null,"Ambiguous episode must not be guessed");
        first.episodeFiles.put("S1E1","001.mkv");
        check(CinemaPlaybackSession.selectFile(new JSONArray().put(file(1,"Pack/001.mkv",10)),"S1E1",first).getInt("id")==1,"Mapped filename at path boundary");
        check(CinemaPlaybackSession.selectFile(new JSONArray().put(file(1,"Pack/1001.mkv",10)),"S1E1",first)==null,"Suffix alone is not a filename match");
        check(CinemaPlaybackSession.selectFile(new JSONArray().put(file(1,"a/001.mkv",10)).put(file(2,"b/001.mkv",20)),"S1E1",first)==null,"Ambiguous mapped filename");
        first.episodeFiles.put("S1E1","S01E02.mkv");
        check(CinemaPlaybackSession.selectFile(files,"S1E1",first)==null,"Metadata cannot override a conflicting episode filename");

        com.sun.net.httpserver.HttpServer http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger redirected=new java.util.concurrent.atomic.AtomicInteger();
        http.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        http.createContext("/target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(new byte[]{'{','}'});exchange.close();});
        http.createContext("/trickle",exchange->{exchange.sendResponseHeaders(200,0);try{for(int i=0;i<40;i++){exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();Thread.sleep(30);}}catch(Exception ignored){}finally{exchange.close();}});
        http.start();String base="http://127.0.0.1:"+http.getAddress().getPort();
        try {
            try{Net.requestUntil(base+"/redirect",null,100,"",System.nanoTime()+TimeUnit.SECONDS.toNanos(2),false);throw new AssertionError("Discovery followed a redirect");}catch(java.io.IOException expected){}
            check(redirected.get()==0,"Do not follow redirects for public metadata either");
            long started=System.nanoTime();
            try{Net.requestUntil(base+"/trickle",null,10000,"",started+TimeUnit.MILLISECONDS.toNanos(200),false);throw new AssertionError("Trickling defeated deadline");}catch(java.io.IOException expected){}
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000,"Shared hard deadline must interrupt a slow body");
        }finally{http.stop(0);}
        System.out.println("CinemaHall source preparation checks passed");
    }
}

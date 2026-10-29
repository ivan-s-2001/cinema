package app.cinemahall.tv;

import org.json.*;

/** Offline contract check: IDs, catalogue registration and detail collisions. No external services. */
public final class ProviderCheck {
    public static void main(String[] args) throws Exception {
        JSONObject manifest=CinemaCatalog.manifest();
        if(!manifest.getString("id").equals("app.cinemahall.catalog"))throw new AssertionError("Provider identity");
        JSONArray catalogs=manifest.getJSONArray("catalogs");
        if(catalogs.length()!=40)throw new AssertionError("All our shelves must be registered");
        for(int i=0;i<catalogs.length();i++){
            JSONObject row=catalogs.getJSONObject(i);
            if(row.getJSONArray("extra").length()!=2)throw new AssertionError("Search and paging must be declared");
        }
        String[] serial=CinemaCatalog.identity("ch:447301:2:7");
        if(!serial[0].equals("ch:447301")||!serial[1].equals("S2E7"))throw new AssertionError("Episode route");
        String snapshot="ch:snapshot:tv:tmdb:42:collision";
        if(!CinemaCatalog.identity(snapshot)[0].equals(snapshot))throw new AssertionError("Snapshot IDs must remain namespaced");
        for(String invalid:new String[]{"tt447301","../settings","ch:a/../../play","ch:"}){
            try{CinemaCatalog.identity(invalid);throw new AssertionError("Unsafe ID accepted");}catch(IllegalArgumentException expected){}catch(Exception expected){}
        }
        JSONObject row=new JSONObject().put("type","movie").put("ids",new JSONObject().put("tmdb","42").put("kinopoisk","999")).put("collisionSuffix","alternate");
        if(!CinemaSnapshot.stableId(row).equals("movie:tmdb:42:alternate"))throw new AssertionError("Conflict suffix lost");
        JSONArray episodes=new JSONArray().put(new JSONObject().put("season",1).put("episode",1).put("title","Known title"));
        CinemaCatalog.mergeKnownEpisodes(snapshot,episodes,"S01E01 S04E02 S04E02 S00E00");
        if(episodes.length()!=2||!episodes.getJSONObject(0).getString("title").equals("Known title")||!episodes.getJSONObject(1).getString("id").equals(snapshot+":4:2"))throw new AssertionError("Detail budget must not hide later seasons or overwrite episode metadata");
        JSONArray pictures=new JSONArray().put(new JSONObject().put("thumbnail","https://example.org/still.jpg")).put(new JSONObject());
        CinemaCatalog.fillEpisodeArtwork(pictures,"https://example.org/wide.jpg","https://example.org/poster.jpg");
        if(!pictures.getJSONObject(0).getString("thumbnail").endsWith("still.jpg")||!pictures.getJSONObject(1).getString("thumbnail").endsWith("wide.jpg"))throw new AssertionError("Episode still must take precedence over series backdrop");
        JSONArray missing=new JSONArray().put(new JSONObject());
        CinemaCatalog.fillEpisodeArtwork(missing,"https://example.org/poster.jpg","https://example.org/poster.jpg");
        if(missing.getJSONObject(0).has("thumbnail"))throw new AssertionError("Portrait cannot be used as episode still");
        JSONObject metadata=new JSONObject();
        CinemaSnapshot.appendMetadata(metadata,new JSONObject().put("ids",new JSONObject().put("imdb","tt1234567"))
            .put("runtimeMinutes",125).put("genres",new JSONArray().put("драма"))
            .put("countries",new JSONArray().put("Россия")).put("ageRating","16+").put("releaseDate","2024-05-01"));
        if(!metadata.getString("runtime").equals("125")||!metadata.getString("imdb_id").equals("tt1234567")||!metadata.getString("country").equals("Россия")||!metadata.getJSONObject("app_extras").getString("certificationLocal").equals("16+"))throw new AssertionError("Available metadata should reach the TV client");
        JSONObject remoteCard=CinemaRemoteCatalog.compact(new JSONObject().put("id","ch:snapshot:movie:tmdb:550").put("type","movie").put("title","Фильм")
            .put("poster","https://cinemahome.duckdns.org/api/v2/images/tmdb/w342/poster.jpg").put("description","Описание")
            .put("genres",new JSONArray().put("драма")).put("runtimeMinutes",125).put("ageRating","16+")
            .put("ratings",new JSONArray().put(new JSONObject().put("source","imdb").put("value",8.2))));
        if(!remoteCard.getString("description").equals("Описание")||remoteCard.getJSONArray("genres").length()!=1||!remoteCard.getString("imdbRating").equals("8.2")||remoteCard.getJSONArray("cinemaRatings").length()!=1||!remoteCard.getString("poster").contains("/api/v2/images/"))throw new AssertionError("Server card metadata and server-hosted posters must reach the UI");
        JSONObject bilingual=new JSONObject().put("title","English title").put("titleRu","Русское название").put("originalTitle","Original title");
        if(!CinemaSnapshot.title(bilingual).equals("Русское название"))throw new AssertionError("Playback search should prefer the verified Russian title");
        bilingual.remove("titleRu");
        if(!CinemaSnapshot.title(bilingual).equals("English title"))throw new AssertionError("Non-Russian titles must remain as fallbacks");
        JSONObject ratings=new JSONObject();
        CinemaRatings.append(ratings,new JSONObject().put("kinopoisk",new JSONObject().put("value",8.2)).put("imdb",7.4).put("anilist",85).put("tmdb",25).put("mal",JSONObject.NULL));
        JSONArray ratingItems=ratings.getJSONArray("cinemaRatings");
        if(ratingItems.length()!=3||!ratingItems.getJSONObject(0).getString("source").equals("kinopoisk")||ratingItems.getJSONObject(2).getInt("scale")!=100||!ratings.getString("imdbRating").equals("7.4"))throw new AssertionError("Ratings must preserve platform and scale and omit invalid values");
        JSONObject more=new JSONObject();
        CinemaRatings.append(more,new JSONObject().put("shikimori",7.9).put("omdb",new JSONArray()
            .put(new JSONObject().put("Source","Rotten Tomatoes").put("Value","94%"))
            .put(new JSONObject().put("Source","Metacritic").put("Value","88/100"))));
        JSONArray extraRatings=more.getJSONArray("cinemaRatings");
        if(extraRatings.length()!=3||!extraRatings.getJSONObject(0).getString("source").equals("shikimori")||extraRatings.getJSONObject(1).getDouble("value")!=94||extraRatings.getJSONObject(2).getDouble("value")!=88)throw new AssertionError("Shikimori and OMDb ratings must retain their own scale");
        if(!Logic.voiceKey("Lost Film многоголосый").equals(Logic.voiceKey("LostFilm"))||Logic.voiceKey("LostFilm / NovaFilm").equals(Logic.voiceKey("LostFilm")))throw new AssertionError("Normalize one studio without merging multiple studios");
        if(Logic.voiceKey("LostFilm / Jaskier").equals(Logic.voiceKey("LostFilm")))throw new AssertionError("Unknown second studio must not disappear");
        Sources.Candidate dv=new Sources.Candidate(new JSONObject().put("resolution","2160p").put("translate_info","LostFilm").put("name","Film 2160p Dolby Vision"));
        Sources.Candidate plain=new Sources.Candidate(new JSONObject().put("resolution","2160p").put("translate_info","LostFilm").put("name","Film 2160p"));
        java.util.List<Sources.Candidate> variants=java.util.Arrays.asList(dv,plain);
        if(Sources.grouped(variants,"4K").get("4K").size()!=2||Sources.ordered(variants,"4K","lostfilm","4K",Sources.technologyKey(dv)).size()!=1)throw new AssertionError("A selected DV variant must never silently fall back to a different technology");
        // Actual video bytes, rather than torrent metadata, establish that a source is ready.
        com.sun.net.httpserver.HttpServer http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/bytes",exchange->{
            if(!"bytes=0-16383".equals(exchange.getRequestHeaders().getFirst("Range")))throw new AssertionError("Bounded byte probe");
            byte[] first={1,2,3,4};exchange.sendResponseHeaders(206,first.length);exchange.getResponseBody().write(first);exchange.close();
        });
        http.createContext("/empty",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();});
        http.createContext("/failure",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        http.createContext("/trickle",exchange->{
            exchange.sendResponseHeaders(200,0);
            try{for(int i=0;i<40;i++){exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();Thread.sleep(30);}}catch(Exception ignored){}finally{exchange.close();}
        });
        http.start();String base="http://127.0.0.1:"+http.getAddress().getPort();
        try {
            Net.verifyStreamBytes(base+"/bytes",1000);
            for(String path:new String[]{"/empty","/failure"}){
                try{Net.verifyStreamBytes(base+path,1000);throw new AssertionError("Empty or failed video must not be ready");}catch(java.io.IOException expected){}
            }
            long started=System.nanoTime();
            try{Net.requestUntil(base+"/trickle",null,10000,"",started+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200));throw new AssertionError("A trickling response must not defeat the total deadline");}catch(java.io.IOException expected){}
            if(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>1000)throw new AssertionError("Total network deadline exceeded");
        }finally{http.stop(0);}
        System.out.println("CinemaHall provider contract passed");
    }
}

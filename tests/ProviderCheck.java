package app.cinemahall.tv;

import org.json.*;

/** Offline contract check: IDs, catalogue registration and detail collisions. No external services. */
public final class ProviderCheck {
    public static void main(String[] args) throws Exception {
        JSONObject manifest=CinemaCatalog.manifest();
        if(!manifest.getString("id").equals("app.cinemahall.catalog"))throw new AssertionError("Provider identity");
        JSONArray catalogs=manifest.getJSONArray("catalogs");
        if(catalogs.length()!=8)throw new AssertionError("All our shelves must be registered");
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
        JSONObject artResult=new JSONObject();
        JSONObject art=new JSONObject().put("poster", "https://example.org/poster.jpg")
            .put("backdrop", "https://example.org/poster.jpg")
            .put("backdrops",new JSONArray().put(JSONObject.NULL).put("invalid").put("https://example.org/poster.jpg").put("https://example.org/wide.jpg"));
        KinoApi.enrich(artResult,new JSONObject().put("images",art),false);
        if(!artResult.getString("background").equals("https://example.org/wide.jpg"))throw new AssertionError("Skip invalid/portrait backdrop candidates");
        art.remove("backdrops");artResult=new JSONObject();
        KinoApi.enrich(artResult,new JSONObject().put("images",art),false);
        if(artResult.has("background"))throw new AssertionError("Poster cannot become background");
        System.out.println("CinemaHall provider contract passed");
    }
}

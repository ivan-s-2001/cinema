package app.cinemahall.tv;

import org.json.*;
import java.util.Locale;

/** Known rating sources retain their own scale; missing and invalid values are omitted. */
final class CinemaRatings {
    private static final String[][] SOURCES = {
        {"kinopoisk", "Кинопоиск", "10", "kinopoisk", "kp"},
        {"imdb", "IMDb", "10", "imdb"},
        {"tmdb", "TMDB", "10", "tmdb"},
        {"myanimelist", "MyAnimeList", "10", "myAnimeList", "myanimelist", "mal"},
        {"anilist", "AniList", "100", "anilist", "aniList"},
        {"rottentomatoes", "Rotten Tomatoes", "100", "rottenTomatoes", "rottentomatoes", "tomatoes"},
        {"metacritic", "Metacritic", "100", "metacritic"}
    };
    static double value(Object raw) {
        if(raw instanceof JSONObject){JSONObject item=(JSONObject)raw;raw=item.has("value")?item.opt("value"):item.opt("rating");}
        if(raw instanceof Number)return ((Number)raw).doubleValue();
        if(raw instanceof String)try{return Double.parseDouble(((String)raw).trim().replace(',', '.'));}catch(NumberFormatException ignored){}
        return Double.NaN;
    }
    static void append(JSONObject out,JSONObject scores) throws JSONException {
        if(scores==null)return;
        JSONArray ratings=new JSONArray();
        for(String[] source:SOURCES){
            int scale=Integer.parseInt(source[2]);double rating=Double.NaN;
            for(int i=3;i<source.length;i++){
                double candidate=value(scores.opt(source[i]));
                if(!Double.isNaN(candidate)&&candidate>0&&candidate<=scale){rating=candidate;break;}
            }
            if(Double.isNaN(rating))continue;
            ratings.put(new JSONObject().put("source",source[0]).put("label",source[1]).put("value",rating).put("scale",scale));
            if(source[0].equals("imdb"))out.put("imdbRating",String.format(Locale.ROOT,"%.1f",rating));
        }
        if(ratings.length()>0)out.put("cinemaRatings",ratings);
    }
}

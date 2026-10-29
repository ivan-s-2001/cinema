package app.cinemahall.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Reads artwork retained by earlier versions without requesting external metadata. */
final class Metadata {
    /** Instant fallback from previously fetched metadata; never performs a network request. */
    static JSONObject cachedArtwork(Context context,JSONObject film){
        SharedPreferences prefs=context.getSharedPreferences("metadata_v2",0);
        String identity=film.optString("id");
        if(identity.isEmpty())identity=film.optString("imdb");
        if(identity.isEmpty())identity=film.optString("name_original")+":"+film.optString("name_rus")+":"+film.optInt("year");
        try{return new JSONObject(prefs.getString(identity+":art","{}"));}
        catch(JSONException invalid){return new JSONObject();}
    }
}

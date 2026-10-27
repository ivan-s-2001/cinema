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
        System.out.println("CinemaHall provider contract passed");
    }
}

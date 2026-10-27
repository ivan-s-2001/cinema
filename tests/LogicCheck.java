package app.cinemahall.tv;

public final class LogicCheck {
    public static void main(String[] args) {
        if(!Logic.sameTitle("Game of Thrones",2011,"Game of Thrones","2011-2019")||!Logic.sameTitle("Spider-Man",2002,"Spider Man","2002"))throw new AssertionError("Metadata title normalization");
        if(!Logic.catalogEpisode("S8E1").equals("S08E01")||!Logic.catalogEpisode("OR *:*").isEmpty())throw new AssertionError("Episode query identity");
        if(Logic.sameTitle("Dune",2021,"Dune","1984")||Logic.sameTitle("Inception",2010,"Bikini Inception","2010")||Logic.sameTitle("Inception",2010,"Inception","")||Logic.sameTitle("",2010,"","2010"))throw new AssertionError("Unrelated metadata accepted");
        String query=Logic.searchQuery("  Dune\" OR *:*  ");
        if(!query.contains("Dune\\\" OR \\*\\:\\*"))throw new AssertionError("Solr operators must be escaped");
        if(!Logic.isVideo("Season 1/episode.MKV")||Logic.isVideo("poster.jpg")||Logic.isVideo("movie.mkv.exe"))throw new AssertionError("Wrong video selection");
        if(!Logic.validLink("magnet:?xt=urn:btih:0123456789abcdef")||Logic.validLink("file:///etc/passwd")||Logic.validLink("https://user:pass@example.com/torrent"))throw new AssertionError("Unsafe link accepted");
        if(!Logic.qualityLabel("1920x800").equals("1080p")||!Logic.qualityLabel("3840x1600").equals("4K")||!Logic.qualityLabel("").equals("Не указано"))throw new AssertionError("Quality tier");
        if(!Logic.qualityLabel("2560x1440").equals("2K")||!Logic.qualityLabel("1280x544").equals("720p")||!Logic.qualityLabel("720x576").equals("576p")||!Logic.qualityLabel("720x304").equals("480p")||!Logic.qualityLabel("640x360").equals("360p")||!Logic.qualityLabel("426x240").equals("240p"))throw new AssertionError("Standard quality tabs");
        if(!Logic.qualityFromTitle("Film.2160p.WEB-DL.mkv").equals("4K")||!Logic.qualityFromTitle("Film.1080p.BluRay").equals("1080p")||!Logic.qualityFromTitle("Film 2026").equals("Не указано"))throw new AssertionError("Tracker quality is a hint only");
        if(!Logic.infoHash("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=test").equals("0123456789abcdef0123456789abcdef01234567"))throw new AssertionError("Torrent infohash deduplication");
        java.util.List<String> queries=Logic.torrentQueries(java.util.Arrays.asList("Бойцовский клуб","Fight Club","Бойцовский клуб"),1999,"");
        if(queries.size()!=3||!queries.get(0).equals("Бойцовский клуб 1999")||!queries.get(1).equals("Fight Club 1999")||!queries.get(2).equals("Бойцовский клуб"))throw new AssertionError("Multi-language search bounds");
        if(!Logic.torrentQueries(java.util.Arrays.asList("Шоу"),2020,"S1E2").get(0).equals("Шоу S01E02"))throw new AssertionError("Episode-aware search");
        if(Logic.seekTarget(5000,30000,-10000)!=0||Logic.seekTarget(25000,30000,10000)!=30000||Logic.seekTarget(10000,-1,10000)!=20000)throw new AssertionError("Seek bounds");
        if(!Logic.time(3661000).equals("1:01:01")||!Logic.time(-1).equals("00:00"))throw new AssertionError("Timeline");
        if(Logic.naturalCompare("episode2.mkv","episode10.mkv")>=0||Logic.naturalCompare("S01E02.mkv","S01E01.mkv")<=0)throw new AssertionError("Episode ordering");
        if(!Logic.episodeKey("Title.S02E10.1080p.mkv").equals("S2E10")||!Logic.episodeLabel("random.mkv",2).equals("Часть 3"))throw new AssertionError("Episode identity");
        if(Logic.sourceScore("1080p","ru","",20,"4K")<=Logic.sourceScore("4K","fr","дубляж",200,"4K"))throw new AssertionError("Foreign audio must not outrank Russian audio");
        if(Logic.sourceScore("4K","ru","",20,"4K")<=Logic.sourceScore("1080p","ru","",20,"4K"))throw new AssertionError("Respect quality preference");
        if(!Logic.voiceKey("  ДУБЛЯЖ  ").equals(Logic.voiceKey("Дублированный"))||!Logic.voiceKey("Кубик в Кубе").equals(Logic.voiceKey("кубик-в-кубе")))throw new AssertionError("Equivalent voice spellings not deduplicated");
        if(Logic.voiceKey("Студия A").equals(Logic.voiceKey("Студия B"))||Logic.qualityRank("4K")>=Logic.qualityRank("1080p"))throw new AssertionError("Distinct voices or quality order lost");
        System.out.println("Logic checks passed");
    }
}

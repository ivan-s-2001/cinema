package app.cinemahall.tv;

public final class LogicCheck {
    public static void main(String[] args) {
        String query=Logic.searchQuery("  Dune\" OR *:*  ");
        if(!query.contains("Dune\\\" OR \\*\\:\\*"))throw new AssertionError("Solr operators must be escaped");
        if(!Logic.isVideo("Season 1/episode.MKV")||Logic.isVideo("poster.jpg")||Logic.isVideo("movie.mkv.exe"))throw new AssertionError("Wrong video selection");
        if(!Logic.validLink("magnet:?xt=urn:btih:0123456789abcdef")||Logic.validLink("file:///etc/passwd")||Logic.validLink("https://user:pass@example.com/torrent"))throw new AssertionError("Unsafe link accepted");
        if(!Logic.qualityLabel("1920x800").equals("1080p")||!Logic.qualityLabel("3840x1600").equals("4K")||!Logic.qualityLabel("").equals("Не указано"))throw new AssertionError("Quality tier");
        if(Logic.seekTarget(5000,30000,-10000)!=0||Logic.seekTarget(25000,30000,10000)!=30000||Logic.seekTarget(10000,-1,10000)!=20000)throw new AssertionError("Seek bounds");
        if(!Logic.time(3661000).equals("1:01:01")||!Logic.time(-1).equals("00:00"))throw new AssertionError("Timeline");
        if(Logic.naturalCompare("episode2.mkv","episode10.mkv")>=0||Logic.naturalCompare("S01E02.mkv","S01E01.mkv")<=0)throw new AssertionError("Episode ordering");
        if(!Logic.episodeKey("Title.S02E10.1080p.mkv").equals("S2E10")||!Logic.episodeLabel("random.mkv",2).equals("Часть 3"))throw new AssertionError("Episode identity");
        if(Logic.sourceScore("1080p","ru","",20,"4K")<=Logic.sourceScore("4K","fr","дубляж",200,"4K"))throw new AssertionError("Foreign audio must not outrank Russian audio");
        if(Logic.sourceScore("4K","ru","",20,"4K")<=Logic.sourceScore("1080p","ru","",20,"4K"))throw new AssertionError("Respect quality preference");
        System.out.println("Logic checks passed");
    }
}

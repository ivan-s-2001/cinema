package app.cinemahall.tv;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.*;

public final class Logic {
    private Logic() {}
    public static boolean sameTitle(String title,int year,String candidate,String release){
        String a=title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
        String b=candidate.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
        return !a.isEmpty()&&a.equals(b)&&year>1800&&release.matches("^"+year+"(?:\\D.*)?$");
    }
    public static String searchQuery(String input) {
        StringBuilder escaped = new StringBuilder();
        for (char c : input.trim().toCharArray()) {
            if ("+-!():^[]\"{}~*?|&\\/".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return "(name_rus:\"" + escaped + "\" OR name_original:\"" + escaped + "\" OR name_eng:\"" + escaped + "\")";
    }
    public static boolean isVideo(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm)$");
    }
    public static boolean validLink(String value) {
        try {
            URI uri = URI.create(value);
            if ("magnet".equalsIgnoreCase(uri.getScheme()))
                return value.toLowerCase(Locale.ROOT).contains("xt=urn:btih:");
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
    public static String qualityLabel(String resolution) {
        Matcher m=Pattern.compile("(\\d{2,5})\\s*[xх×]\\s*(\\d{2,5})",Pattern.CASE_INSENSITIVE).matcher(resolution);
        if(!m.find())return "Не указано";
        int width=Integer.parseInt(m.group(1)),height=Integer.parseInt(m.group(2));
        if(width>=3600||height>=2000)return "4K";
        if(width>=2500||height>=1400)return "2K";
        if(width>=1800||height>=1000)return "1080p";
        if(width>=1200||height>=700)return "720p";
        if(height>=550)return "576p";
        if(width>=700||height>=450)return "480p";
        if(width>=600||height>=330)return "360p";
        return "240p";
    }
    /** Tracker titles report quality; it is only a hint until a video stream is inspected. */
    public static String qualityFromTitle(String title) {
        String value=title==null?"":title.toLowerCase(Locale.ROOT);
        if(Pattern.compile("(?<!\\d)(2160p?|4k|uhd)(?!\\d)").matcher(value).find())return "4K";
        if(Pattern.compile("(?<!\\d)(1440p?|2k)(?!\\d)").matcher(value).find())return "2K";
        for(String p:new String[]{"1080","720","576","480","360","240"})
            if(Pattern.compile("(?<!\\d)"+p+"p?(?!\\d)").matcher(value).find())return p+"p";
        return "Не указано";
    }
    /** Labels inferred from a release name, not verified video/audio stream capabilities. */
    public static List<String> releaseTechnologyHints(String raw) {
        String title=raw==null?"":raw.toUpperCase(Locale.ROOT);
        LinkedHashSet<String> labels=new LinkedHashSet<>();
        if(Pattern.compile("(?<![A-Z0-9])(?:DOLBY[ ._-]*VISION|DOVI|DV)(?![A-Z0-9])").matcher(title).find())labels.add("Dolby Vision");
        if(Pattern.compile("(?<![A-Z0-9])HDR[ ._-]*10\\+|(?<![A-Z0-9])HDR10PLUS").matcher(title).find())labels.add("HDR10+");
        else if(Pattern.compile("(?<![A-Z0-9])(?:HDR10|HDR)(?![A-Z0-9])").matcher(title).find())labels.add("HDR");
        if(Pattern.compile("(?<![A-Z0-9])HLG(?![A-Z0-9])").matcher(title).find())labels.add("HLG");
        if(Pattern.compile("(?<![A-Z0-9])(?:HEVC|H[ ._-]*265|X265)(?![A-Z0-9])").matcher(title).find())labels.add("HEVC");
        else if(Pattern.compile("(?<![A-Z0-9])(?:AVC|H[ ._-]*264|X264)(?![A-Z0-9])").matcher(title).find())labels.add("H.264");
        else if(Pattern.compile("(?<![A-Z0-9])AV1(?![A-Z0-9])").matcher(title).find())labels.add("AV1");
        if(Pattern.compile("(?<![A-Z0-9])ATMOS(?![A-Z0-9])").matcher(title).find())labels.add("Dolby Atmos");
        if(Pattern.compile("(?<![A-Z0-9])DTS[ ._-]*X(?![A-Z0-9])").matcher(title).find())labels.add("DTS:X");
        else if(Pattern.compile("(?<![A-Z0-9])DTS[ ._-]*HD(?![A-Z0-9])").matcher(title).find())labels.add("DTS-HD");
        if(Pattern.compile("(?<![A-Z0-9])(?:TRUEHD|TRUE[ ._-]*HD)(?![A-Z0-9])").matcher(title).find())labels.add("TrueHD");
        if(Pattern.compile("(?<![A-Z0-9])(?:EAC3|E[ ._-]*AC[ ._-]*3|DDP)(?![A-Z0-9])").matcher(title).find())labels.add("DD+");
        if(Pattern.compile("(?<![A-Z0-9])(?:WEB[ ._-]*DL|WEBDL)(?![A-Z0-9])").matcher(title).find())labels.add("WEB-DL");
        else if(Pattern.compile("(?<![A-Z0-9])(?:BLURAY|BLU[ ._-]*RAY|BDREMUX|REMUX)(?![A-Z0-9])").matcher(title).find())labels.add("Blu-ray");
        return new ArrayList<>(labels);
    }
    public static String infoHash(String link) {
        Matcher match=Pattern.compile("(?i)(?:[?&])xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})(?:&|$)").matcher(link==null?"":link);
        return match.find()?match.group(1).toLowerCase(Locale.ROOT):"";
    }
    /** Two language variants plus one broad fallback; bound network calls on Android TV. */
    public static List<String> torrentQueries(List<String> aliases,int year,String episode) {
        LinkedHashSet<String> names=new LinkedHashSet<>();
        if(aliases!=null)for(String raw:aliases){
            String name=raw==null?"":raw.trim().replaceAll("\\s+"," ");
            if(name.length()>=3&&name.length()<=100)names.add(name);
            if(names.size()==2)break;
        }
        LinkedHashSet<String> queries=new LinkedHashSet<>();
        String key=catalogEpisode(episode==null?"":episode);
        for(String name:names){
            queries.add(name+(key.isEmpty()?(year>=1880&&year<=2100?" "+year:""):" "+key));
        }
        if(!names.isEmpty())queries.add(names.iterator().next());
        return new ArrayList<>(queries);
    }
    public static boolean torrentMatches(String candidate,List<String> aliases){
        String title=torrentWords(candidate);
        if(aliases==null)return false;
        for(String alias:aliases){
            String name=torrentWords(alias);
            if(name.length()>=6&&title.contains(name))return true;
        }
        return false;
    }
    private static String torrentWords(String value){
        return " "+(value==null?"":value).toLowerCase(Locale.ROOT).replace('ё','е')
            .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ")+" ";
    }
    public static long seekTarget(long current,long duration,long delta) {
        long target=Math.max(0,current+delta);return duration>0?Math.min(duration,target):target;
    }
    public static String time(long millis) {
        long seconds=Math.max(0,millis/1000),hours=seconds/3600;
        return hours>0?String.format(Locale.ROOT,"%d:%02d:%02d",hours,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
    public static int naturalCompare(String a,String b) {
        Matcher left=Pattern.compile("\\d+|\\D+").matcher(a.toLowerCase(Locale.ROOT)),right=Pattern.compile("\\d+|\\D+").matcher(b.toLowerCase(Locale.ROOT));
        while(left.find()&&right.find()){
            String x=left.group(),y=right.group();int result;
            if(Character.isDigit(x.charAt(0))&&Character.isDigit(y.charAt(0)))result=new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            else result=x.compareTo(y);
            if(result!=0)return result;
        }
        return a.length()-b.length();
    }
    public static String episodeKey(String path){
        Matcher both=Pattern.compile("(?i)s(\\d{1,3})[ ._-]*e(\\d{1,4})").matcher(path);
        if(both.find())return "S"+Integer.parseInt(both.group(1))+"E"+Integer.parseInt(both.group(2));
        Matcher episode=Pattern.compile("(?i)(?:\\be|серия[ ._-]*|эпизод[ ._-]*)(\\d{1,4})").matcher(path);
        return episode.find()?"E"+Integer.parseInt(episode.group(1)):"";
    }
    public static String episodeLabel(String path,int index){
        String key=episodeKey(path);if(key.isEmpty())return "Часть "+(index+1);
        if(key.startsWith("S")){int e=key.indexOf('E');return "Сезон "+key.substring(1,e)+" · Серия "+key.substring(e+1);}
        return "Серия "+key.substring(1);
    }
    public static String catalogEpisode(String episode){
        Matcher match=Pattern.compile("S(\\d+)E(\\d+)").matcher(episode);
        return match.matches()?String.format(Locale.ROOT,"S%02dE%02d",Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2))):"";
    }
    public static int sourceScore(String quality,String languages,String translation,int seeds,String target){
        String lang=languages.toLowerCase(Locale.ROOT),dub=translation.toLowerCase(Locale.ROOT);
        boolean russian=Pattern.compile("(?:^|[^a-z])(?:ru|rus)(?:$|[^a-z])").matcher(lang).find();
        boolean otherKnown=!lang.trim().isEmpty()&&!russian;
        boolean inferred=!otherKnown&&!dub.contains("украин")&&(dub.contains("дубляж")||dub.contains("закадров")||dub.contains("одноголос")||dub.contains("многоголос")||dub.contains("русск"));
        int language=russian?1000:inferred?900:lang.trim().isEmpty()?600:0;
        int resolution=quality.equals(target)?300:quality.equals("2K")?240:quality.equals("1080p")?200:quality.equals("720p")?120:quality.equals("4K")?100:quality.equals("576p")?90:quality.equals("480p")?80:30;
        return language+resolution+(int)Math.min(80,Math.log1p(Math.max(0,seeds))*15);
    }
    /** A studio remains distinct; only spelling/punctuation and unequivocal aliases collapse. */
    public static String voiceKey(String label){
        String key=Normalizer.normalize(label==null?"":label,Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ");
        for(String[] alias:new String[][]{{"lost\\s*film","lostfilm"},{"nova\\s*film","novafilm"},{"(?:ren\\s*tv|рен\\s*тв)","ren tv"},{"sony\\s*sci\\s*fi","sony sci fi"},{"cold\\s*film","coldfilm"}}){
            key=key.replaceAll("(?<![\\p{L}\\p{N}])(?:"+alias[0]+")(?![\\p{L}\\p{N}])",alias[1]);
        }
        // Only a whole, known studio label may lose an unambiguous descriptor.
        // A second unknown studio, dubbing type or qualifier must stay distinct.
        key=key.replaceFirst("^(lostfilm|novafilm|ren tv|sony sci fi|coldfilm) (?:многоголосый(?: закадровый)?|mvo)$","$1");
        if(key.equals("дубляж"))return "дублированный";
        if(key.equals("original")||key.equals("оригинальный"))return "оригинал";
        return key.isEmpty()?"не указано":key;
    }
    public static int qualityRank(String value){
        String[] levels={"4K","2K","1080p","720p","576p","480p","360p","240p","Не указано"};
        for(int i=0;i<levels.length;i++)if(levels[i].equals(value))return i;
        return levels.length;
    }
}

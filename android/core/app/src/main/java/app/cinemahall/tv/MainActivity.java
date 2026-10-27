package app.cinemahall.tv;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Small native bring-up UI. Streambert's Electron renderer is not an Android runtime. */
public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private int generation, page;
    private boolean series;
    private String query="";
    private LinearLayout body;
    private TextView status;
    private JSONObject selected;
    private interface Job { void run(int ticket) throws Exception; }
    @Override public void onCreate(Bundle state){super.onCreate(state);home();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.rgb(245,245,247));v.setPadding(dp(8),dp(8),dp(8),dp(8));return v;
    }
    private void screen(String title){
        generation++;if(pending!=null)pending.cancel(true);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int inset=getResources().getConfiguration().screenWidthDp>=600?40:16;
        body.setPadding(dp(inset),dp(24),dp(inset),dp(32));scroll.addView(body);setContentView(scroll);
        body.addView(text(title,28));status=text("",16);body.addView(status);
    }
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(18);b.setMinHeight(dp(56));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(4),0,dp(4));body.addView(b,params);
        b.setOnClickListener(v->action.run());return b;
    }
    private void post(int ticket,Runnable action){runOnUiThread(()->{if(!isDestroyed()&&ticket==generation)action.run();});}
    private void task(String label,Job job,Runnable retry){
        int ticket=++generation;if(pending!=null)pending.cancel(true);status.setText(label);
        pending=worker.submit(()->{try{job.run(ticket);}catch(Exception error){post(ticket,()->{
            status.setText("Не удалось загрузить. "+(error.getMessage()==null?"Проверьте соединение.":error.getMessage()));
            button("Повторить",retry);
        });}});
    }
    private void home(){
        selected=null;screen("CinemaHall");body.addView(text("Фильмы и сериалы",18));
        button("Фильмы",()->{query="";series=false;page=0;catalog();});button("Сериалы",()->{query="";series=true;page=0;catalog();});
        body.addView(text("Поиск по русскому и оригинальному названию",16));
        EditText search=new EditText(this);search.setSingleLine();search.setHint("Название фильма или сериала");body.addView(search);
        button("Найти",()->{query=search.getText().toString().trim();if(query.length()<2){search.setError("Введите хотя бы 2 символа");return;}page=0;catalog();});
        button("Настройки источников",this::settings);
    }
    private void catalog(){
        screen(query.isEmpty()?(series?"Сериалы":"Фильмы"):"Поиск: "+query);button("На главную",()->{query="";home();});
        task("Загрузка каталога…",ticket->{
            String filter="playable:true AND trailer:false AND adult:false AND tor_count:[1 TO *] AND "+(query.isEmpty()?"serial:"+series:Logic.searchQuery(query));
            JSONObject response=Net.select("movie",filter,"popularity desc",page*30,30,"id,name_rus,name_original,name_eng,year,serial,description,rating_imdb");
            JSONArray rows=response.getJSONArray("docs");post(ticket,()->{
                status.setText(rows.length()==0?"Ничего не найдено":"Страница "+(page+1));
                for(int i=0;i<rows.length();i++){JSONObject film=rows.optJSONObject(i);if(film==null)continue;
                    button(film.optString("name_rus",film.optString("name_original"))+" · "+film.optString("year"),()->detail(film));}
                if(page>0)button("Предыдущая страница",()->{page--;catalog();});
                if((page+1)*30<response.optInt("numFound"))button("Следующая страница",()->{page++;catalog();});
            });
        },this::catalog);
    }
    private void detail(JSONObject film){
        selected=film;screen(film.optString("name_rus",film.optString("name_original")));button("Назад к каталогу",this::catalog);
        body.addView(text(film.optString("name_original")+" · "+film.optString("year"),18));
        body.addView(text(film.optString("description","Описание отсутствует"),18));
        if(film.optBoolean("serial")){
            body.addView(text("Выберите сезон и серию",18));
            EditText season=new EditText(this);season.setInputType(2);season.setText("1");season.setContentDescription("Сезон");body.addView(season);
            EditText episode=new EditText(this);episode.setInputType(2);episode.setText("1");episode.setContentDescription("Серия");body.addView(episode);
            button("Качество и озвучка",()->{
                try{int s=Integer.parseInt(season.getText().toString()),e=Integer.parseInt(episode.getText().toString());
                    if(s<1||s>999||e<1||e>9999)throw new NumberFormatException();sources(film,"S"+s+"E"+e);
                }catch(NumberFormatException error){status.setText("Введите корректный сезон и серию");}
            });
        }else button("Качество и озвучка",()->sources(film,""));
    }
    private void engine() throws Exception {
        if(!TorrentService.ready){
            startForegroundService(new Intent(this,TorrentService.class));
            long deadline=System.currentTimeMillis()+20000;
            while(!TorrentService.ready&&System.currentTimeMillis()<deadline){Thread.sleep(200);if(TorrentService.failure!=null)throw new Exception(TorrentService.failure);}
            if(!TorrentService.ready)throw new Exception("Не удалось запустить движок");
        }
    }
    private void sources(JSONObject film,String episode){
        task("Поиск вариантов…",ticket->{
            engine();ArrayList<String> aliases=new ArrayList<>();for(String field:new String[]{"name_rus","name_original","name_eng"})aliases.add(film.optString(field));
            ArrayList<Sources.Candidate> choices=Sources.load(film.optString("id"),episode,aliases,film.optInt("year"));
            post(ticket,()->{
                status.setText(choices.isEmpty()?"Доступные варианты не найдены":"Качество заявлено источником");
                LinkedHashMap<String,LinkedHashMap<String,ArrayList<Sources.Candidate>>> groups=Sources.grouped(choices,"1080p");
                String[] qualities=groups.keySet().toArray(new String[0]);
                if(qualities.length>0)new AlertDialog.Builder(this).setTitle("Качество").setItems(qualities,(d,n)->{
                    ArrayList<ArrayList<Sources.Candidate>> voices=new ArrayList<>(groups.get(qualities[n]).values());
                    String[] labels=new String[voices.size()];for(int i=0;i<labels.length;i++){String voice=voices.get(i).get(0).translation;labels[i]=voice.isEmpty()?"Не указано":voice;}
                    new AlertDialog.Builder(this).setTitle("Озвучка · "+qualities[n]).setItems(labels,(dialog,v)->prepare(voices.get(v).get(0),episode)).setNegativeButton("Назад",null).show();
                }).setNegativeButton("Отмена",null).show();
            });
        },()->sources(film,episode));
    }
    private void prepare(Sources.Candidate candidate,String episode){
        task("Подготовка видео…",ticket->{
            JSONObject added=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","add").put("link",candidate.link).put("save_to_db",false));
            String hash=added.optString("hash");if(hash.isEmpty())throw new Exception("Движок не вернул идентификатор видео");
            JSONArray files=null;
            for(int i=0;i<40;i++){
                JSONObject torrent=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","get").put("hash",hash));
                files=torrent.optJSONArray("file_stats");if(files!=null&&files.length()>0)break;Thread.sleep(500);
            }
            ArrayList<JSONObject> videos=new ArrayList<>();
            if(files!=null)for(int i=0;i<files.length();i++){
                JSONObject file=files.optJSONObject(i);if(file!=null&&Logic.isVideo(file.optString("path")))videos.add(file);
            }
            videos.sort((a,b)->Logic.naturalCompare(a.optString("path"),b.optString("path")));
            if(videos.isEmpty())throw new Exception("Список видео пока недоступен. Попробуйте другой вариант.");
            post(ticket,()->{
                status.setText("");
                if(videos.size()==1){play(hash,videos.get(0));return;}
                String[] names=new String[videos.size()];int checked=0;
                for(int i=0;i<names.length;i++){names[i]=videos.get(i).optString("path");if(!episode.isEmpty()&&episode.equals(Logic.episodeKey(names[i])))checked=i;}
                new AlertDialog.Builder(this).setTitle("Выберите серию или файл").setSingleChoiceItems(names,checked,(dialog,n)->{dialog.dismiss();play(hash,videos.get(n));}).setNegativeButton("Отмена",null).show();
            });
        },()->prepare(candidate,episode));
    }
    private void play(String hash,JSONObject file){
        String url=Net.ENGINE+"/stream/video?link="+Net.enc(hash)+"&index="+file.optInt("id")+"&play";
        startActivity(new Intent(this,PlayerActivity.class).putExtra("url",url));
    }
    private void settings(){
        screen("Источники");button("Назад",this::home);
        body.addView(text("Дополнительный поиск через Jackett / Prowlarr. Укажите HTTPS Torznab URL, включая apikey. Ключ сохраняется только на устройстве.",18));
        EditText endpoint=new EditText(this);endpoint.setSingleLine();endpoint.setInputType(129);endpoint.setContentDescription("Torznab URL");
        endpoint.setText(getSharedPreferences("cinemahall_settings",0).getString("torznab",""));body.addView(endpoint);
        button("Сохранить",()->{
            String value=endpoint.getText().toString().trim();
            try{if(!value.isEmpty()){java.net.URI uri=new java.net.URI(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new Exception();}}
            catch(Exception invalid){endpoint.setError("Нужен корректный HTTPS URL");return;}
            task("Сохранение…",ticket->{engine();JSONObject sets=Net.action("/settings","get");
                JSONArray urls=new JSONArray();if(!value.isEmpty())urls.put(value);
                sets.put("TorznabUrls",urls).put("EnableTorznabSearch",!value.isEmpty());
                Net.json(Net.ENGINE+"/settings",new JSONObject().put("action","set").put("sets",sets));
                getSharedPreferences("cinemahall_settings",0).edit().putString("torznab",value).apply();
                post(ticket,()->status.setText("Сохранено"));
            },this::settings);
        });
    }
    @Override public void onBackPressed(){if(selected!=null){selected=null;catalog();}else if(!query.isEmpty()||page>0){query="";page=0;home();}else super.onBackPressed();}
    @Override protected void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}

package app.cinemahall.tv;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile int generation;
    private ExoPlayer player;
    private PlayerView video;
    private FrameLayout root,overlay;
    private LinearLayout bottom;
    private TextView status,timeNow,timeEnd,pauseButton,subtitle;
    private SeekBar timeline;
    private Dialog panel;
    private String hash="",link,title,positionKey,quality;
    private final ArrayList<Sources.Candidate> candidates=new ArrayList<>();
    private final HashSet<String> attempted=new HashSet<>();
    private String desiredQuality="",desiredTranslation="",wantedEpisode="",wantedFilename="",autoTarget="1080p";
    private String sourceEpisodeFilename="";
    private int attempts;
    private boolean selectingSource;
    private ArrayList<JSONObject> files=new ArrayList<>();
    private int fileIndex=-1,resizeIndex;
    private boolean dragging;
    private long pendingPosition;
    private float playbackSpeed=1f;
    private String preferredAudioLanguage="ru";
    private final Runnable hide=()->{if(player!=null&&player.isPlaying()&&panel==null&&!dragging)hideControls();};
    private final Runnable startupTimeout=()->{if(!closed&&player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING)retrySource();};
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        if(player!=null&&timeline!=null){
            long duration=player.getDuration(),position=player.getCurrentPosition();
            timeline.setSecondaryProgress(duration>0?(int)Math.min(1000,player.getBufferedPosition()*1000/duration):0);
            if(!dragging){timeline.setEnabled(duration>0);timeline.setProgress(duration>0?(int)(position*1000/duration):0);timeNow.setText(Logic.time(position));}
            timeEnd.setText(duration>0?Logic.time(duration):"—:—");
            pauseButton.setText(player.isPlaying()?"Пауза":"Смотреть");pauseButton.setContentDescription(player.isPlaying()?"Пауза":"Смотреть");Ui.icon(PlayerActivity.this,pauseButton,player.isPlaying()?R.drawable.ic_pause:R.drawable.ic_play);
        }
        handler.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        title=getIntent().getStringExtra("title");
        wantedEpisode=getIntent().getStringExtra("episode");if(wantedEpisode==null)wantedEpisode="";
        if(wantedEpisode.isEmpty()&&getIntent().getBooleanExtra("serial",false))wantedEpisode=getSharedPreferences("history",0).getString("episode:"+getIntent().getStringExtra("movieId"),"S1E1");
        desiredQuality=getSharedPreferences("playerSettings",0).getString("quality","");
        Display.Mode display=getWindowManager().getDefaultDisplay().getMode();if(Math.max(display.getPhysicalWidth(),display.getPhysicalHeight())>=3800)autoTarget="4K";
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);root.setOnClickListener(v->showControls());setContentView(root);
        loading("Подготавливаем просмотр");TorrentService.failure=null;
        try{startForegroundService(new Intent(this,TorrentService.class));}catch(Exception e){fail(e.getMessage());return;}
        loadSources();handler.post(tick);
    }
    private void loadSources(){
        int token=++generation;
        worker.execute(()->{
            try{
                ArrayList<Sources.Candidate> found=Sources.load(getIntent().getStringExtra("movieId"),wantedEpisode);
                runOnUiThread(()->{if(!closed&&token==generation){candidates.clear();candidates.addAll(found);if(!desiredQuality.isEmpty()){boolean available=false;for(Sources.Candidate c:found)if(c.quality.equals(desiredQuality)){available=true;break;}if(!available)desiredQuality="";}attempted.clear();attempts=0;tryNextSource();}});
            }catch(Exception e){runOnUiThread(()->{if(!closed&&token==generation)fail("Не удалось начать просмотр. Проверьте подключение.");});}
        });
    }
    private void tryNextSource(){
        if(closed)return;
        ArrayList<Sources.Candidate> options=Sources.ordered(candidates,desiredQuality,desiredTranslation,autoTarget);
        Sources.Candidate next=null;
        for(Sources.Candidate candidate:options)if(!attempted.contains(candidate.hash)){next=candidate;break;}
        if(next==null||attempts>=6){selectingSource=false;fail("Просмотр сейчас недоступен. Попробуйте другое качество или повторите позже.");return;}
        attempted.add(next.hash);attempts++;String previous=hash;link=next.link;quality=next.quality;selectingSource=true;
        sourceEpisodeFilename=next.episodeFile(wantedEpisode);
        releasePlayer();loading(attempts==1?"Подготавливаем просмотр":"Подбираем доступное видео…");prepareSource(link,previous);
    }
    private void retrySource(){
        preserveCurrent();
        if(!TorrentService.ready){selectingSource=false;fail("Не удалось подготовить просмотр. Попробуйте снова.");return;}
        tryNextSource();
    }
    private int dp(int value){return Ui.dp(this,value);}
    private void loading(String label){
        root.removeAllViews();overlay=null;timeline=null;
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setGravity(Gravity.CENTER);layout.setPadding(dp(32),dp(24),dp(32),dp(24));
        TextView name=Ui.text(this,title==null?"CinemaHall":title,26,Ui.TEXT);name.setGravity(Gravity.CENTER);layout.addView(name);
        ProgressBar spinner=new ProgressBar(this);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(40),dp(40));sp.setMargins(0,dp(24),0,dp(20));layout.addView(spinner,sp);
        status=Ui.text(this,label,16,Ui.MUTED);layout.addView(status);
        TextView back=Ui.button(this,"Назад",R.drawable.ic_back,false,this::finish);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.topMargin=dp(24);layout.addView(back,bp);back.requestFocus();
        root.addView(layout,new FrameLayout.LayoutParams(-1,-1));
    }
    private void prepareSource(String source,String previousHash){
        final int token=++generation;
        worker.execute(()->{
            try{
                long deadline=System.currentTimeMillis()+45000;
                while(!TorrentService.ready&&!closed){
                    if(TorrentService.failure!=null)throw new Exception(TorrentService.failure);
                if(System.currentTimeMillis()>deadline)throw new Exception("Не удалось подготовить просмотр.");Thread.sleep(250);
                }
                if(closed||token!=generation)return;
                if(!previousHash.isEmpty())try{Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","rem").put("hash",previousHash));}catch(Exception ignored){}
                runOnUiThread(()->{if(token==generation)status.setText("Открываем видео…");});
                JSONObject data=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","add").put("link",source).put("title",title).put("save_to_db",false));
                String newHash=data.optString("hash");if(newHash.trim().isEmpty())throw new Exception("Видео не отвечает");
                JSONArray list=data.optJSONArray("file_stats");deadline=System.currentTimeMillis()+15000;
                while((list==null||list.length()==0)&&!closed&&token==generation){
                    if(System.currentTimeMillis()>deadline)throw new Exception("Видео не отвечает");Thread.sleep(700);
                    data=Net.json(Net.ENGINE+"/torrents",new JSONObject().put("action","get").put("hash",newHash));list=data.optJSONArray("file_stats");
                }
                if(closed||token!=generation)return;
                ArrayList<JSONObject> videos=new ArrayList<>();
                if(list!=null)for(int i=0;i<list.length();i++){JSONObject file=list.getJSONObject(i);String name=file.optString("path");if(Logic.isVideo(name)&&!name.toLowerCase(Locale.ROOT).matches(".*(?:\\bsample\\b|\\btrailer\\b|трейлер).*"))videos.add(file);}
                if(videos.isEmpty())throw new Exception("Не найдено видео");
                videos.sort((a,b)->Logic.naturalCompare(a.optString("path"),b.optString("path")));
                runOnUiThread(()->{if(!closed&&token==generation){link=source;hash=newHash;files=videos;fileIndex=-1;selectingSource=false;chooseAutomatically();}});
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(Exception e){runOnUiThread(()->{if(!closed&&token==generation)retrySource();});}
        });
    }
    private void chooseAutomatically(){
        int index=0;
        if(getIntent().getBooleanExtra("serial",false)){
            String saved=wantedEpisode.isEmpty()?getSharedPreferences("history",0).getString("episode:"+getIntent().getStringExtra("movieId"),""):wantedEpisode;
            if(!saved.isEmpty()){
                index=-1;for(int i=0;i<files.size();i++)if(saved.equals(Logic.episodeKey(files.get(i).optString("path")))){index=i;break;}
                if(index<0&&!sourceEpisodeFilename.isEmpty())for(int i=0;i<files.size();i++){
                    String mapped=sourceEpisodeFilename.substring(sourceEpisodeFilename.lastIndexOf('/')+1);if(mapped.equals(fileName(files.get(i)))){index=i;break;}
                }
                if(index<0){retrySource();return;}
            }else{
                String filename=wantedFilename.isEmpty()?getSharedPreferences("history",0).getString("filename:"+getIntent().getStringExtra("movieId"),""):wantedFilename;
                if(!filename.isEmpty()){
                    index=-1;for(int i=0;i<files.size();i++)if(filename.equals(fileName(files.get(i)))){index=i;break;}
                    if(index<0){retrySource();return;}
                }
            }
        }else{
            for(int i=1;i<files.size();i++)if(files.get(i).optLong("length")>files.get(index).optLong("length"))index=i;
        }
        playFile(index);
    }
    private String fileName(JSONObject file){String path=file.optString("path");int slash=path.lastIndexOf('/');return slash>=0?path.substring(slash+1):path;}
    private void showFiles(){
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        for(int i=0;i<files.size();i++){final int index=i;labels.add(Logic.episodeLabel(files.get(i).optString("path"),i));actions.add(()->{pendingPosition=0;wantedEpisode=Logic.episodeKey(files.get(index).optString("path"));playFile(index);});}
        if(labels.isEmpty()){showSources();return;}
        showPanel("Серии",labels,actions,fileIndex);
    }
    private void savePosition(){
        if(player!=null&&positionKey!=null){
            android.content.SharedPreferences.Editor edit=getSharedPreferences("history",0).edit().putLong(positionKey,player.getPlaybackState()==Player.STATE_ENDED?0:player.getCurrentPosition());
            if(fileIndex>=0&&getIntent().getBooleanExtra("serial",false)){
                edit.putString("episode:"+getIntent().getStringExtra("movieId"),Logic.episodeKey(files.get(fileIndex).optString("path")));
                edit.putString("filename:"+getIntent().getStringExtra("movieId"),fileName(files.get(fileIndex)));
            }
            String filmJson=getIntent().getStringExtra("movieJson"),movieId=getIntent().getStringExtra("movieId");
            if(filmJson!=null&&player.getCurrentPosition()>0&&player.getPlaybackState()!=Player.STATE_ENDED)edit.putString("film:"+movieId,filmJson).putLong("updated:"+movieId,System.currentTimeMillis());
            else if(player.getPlaybackState()==Player.STATE_ENDED)edit.remove("film:"+movieId);
            edit.apply();
        }
    }
    private void releasePlayer(){handler.removeCallbacks(startupTimeout);savePosition();if(player!=null){if(video!=null)video.setPlayer(null);player.release();player=null;}}
    private void playFile(int index){
        if(index<0||index>=files.size())return;
        JSONObject file=files.get(index);int id=file.optInt("id",-1);if(id<1){retrySource();return;}
        releasePlayer();fileIndex=index;
        String movieId=getIntent().getStringExtra("movieId");
        String episode=Logic.episodeKey(file.optString("path"));
        positionKey="position:"+movieId+":"+(getIntent().getBooleanExtra("serial",false)?(episode.isEmpty()?fileName(file):episode):"movie");
        long legacy=getSharedPreferences("history",0).getLong(movieId+":"+hash+":"+id,0);
        long resume=pendingPosition>0?pendingPosition:getSharedPreferences("history",0).getLong(positionKey,legacy);pendingPosition=0;
        String stream=Net.ENGINE+"/stream/video?link="+Net.enc(hash)+"&index="+id+"&play&preload";
        root.removeAllViews();video=new PlayerView(this);video.setUseController(false);video.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        player=new ExoPlayer.Builder(this).build();video.setPlayer(player);
        player.setPlaybackSpeed(playbackSpeed);
        video.setResizeMode(new int[]{AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_ZOOM,AspectRatioFrameLayout.RESIZE_MODE_FILL}[resizeIndex]);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(preferredAudioLanguage).build());
        buildControls();player.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException error){handler.removeCallbacks(hide);retrySource();}
            @Override public void onIsPlayingChanged(boolean playing){if(!playing)showControls();else scheduleHide();}
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY)handler.removeCallbacks(startupTimeout);
                if(state==Player.STATE_BUFFERING){handler.removeCallbacks(startupTimeout);handler.postDelayed(startupTimeout,25000);}
                if(subtitle!=null)subtitle.setText(state==Player.STATE_BUFFERING?"Буферизация…":fileSubtitle());
                if(state==Player.STATE_ENDED&&fileIndex+1<files.size()){
                    ArrayList<String> labels=new ArrayList<>(Arrays.asList("Следующая серия","Остаться"));
                    ArrayList<Runnable> actions=new ArrayList<>();actions.add(()->{pendingPosition=0;playFile(fileIndex+1);});actions.add(()->{});showPanel("Продолжить просмотр?",labels,actions,0);
                }
            }
        });
        player.setMediaItem(MediaItem.fromUri(stream));if(resume>0)player.seekTo(resume);player.prepare();player.play();handler.postDelayed(startupTimeout,25000);showControls();
    }
    private String fileSubtitle(){return (quality==null?"Авто":quality)+(getIntent().getBooleanExtra("serial",false)&&fileIndex>=0?"   ·   "+Logic.episodeLabel(files.get(fileIndex).optString("path"),fileIndex):"");}
    private void buildControls(){
        overlay=new FrameLayout(this);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(28),dp(20),dp(28),dp(32));
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xee000000,0x00000000}));
        TextView back=Ui.button(this,"Назад",R.drawable.ic_back,false,this::finish);header.addView(back);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(20),0,0,0);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView name=Ui.text(this,title,23,Ui.TEXT);name.setMaxLines(1);titles.addView(name);subtitle=Ui.text(this,fileSubtitle(),14,Ui.MUTED);subtitle.setMaxLines(1);titles.addView(subtitle);
        overlay.addView(header,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(28),dp(36),dp(28),dp(20));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xf5000000}));
        overlay.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        LinearLayout times=new LinearLayout(this);times.setGravity(Gravity.CENTER_VERTICAL);
        timeNow=Ui.text(this,"00:00",15,Ui.TEXT);timeNow.setMinWidth(dp(64));times.addView(timeNow);
        timeline=new SeekBar(this);timeline.setMax(1000);timeline.setKeyProgressIncrement(10);timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));timeline.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(0xff727984));timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));timeline.setContentDescription("Позиция воспроизведения");
        times.addView(timeline,new LinearLayout.LayoutParams(0,dp(48),1));timeEnd=Ui.text(this,"—:—",15,Ui.MUTED);timeEnd.setMinWidth(dp(64));timeEnd.setGravity(Gravity.END);times.addView(timeEnd);bottom.addView(times);
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;handler.removeCallbacks(hide);}
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser&&player!=null&&player.getDuration()>0)timeNow.setText(Logic.time(player.getDuration()*progress/1000));}
            public void onStopTrackingTouch(SeekBar bar){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*bar.getProgress()/1000);dragging=false;scheduleHide();}
        });
        timeline.setOnKeyListener((v,key,event)->{
            if((key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)&&event.getAction()==KeyEvent.ACTION_DOWN){seekBy((key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1)*10000L*Math.min(6,1+event.getRepeatCount()/3));return true;}return false;
        });
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(controls);bottom.addView(scroll);
        pauseButton=control(controls,"Пауза",R.drawable.ic_pause,this::toggle);
        control(controls,"Качество и озвучка",R.drawable.ic_quality,this::showSources);
        control(controls,"Субтитры",R.drawable.ic_subtitles,()->tracks(C.TRACK_TYPE_TEXT));
        if(getIntent().getBooleanExtra("serial",false)&&files.size()>1)control(controls,"Серии",R.drawable.ic_list,this::showFiles);
        control(controls,"Скорость",R.drawable.ic_speed,this::speed);
        control(controls,"Экран",R.drawable.ic_fit,this::resize);
        TextView hint=Ui.text(this,"← → перемотка    ОК управление    Назад скрыть панель",12,Ui.MUTED);hint.setPadding(0,dp(8),0,0);bottom.addView(hint);
    }
    private TextView control(LinearLayout row,String label,int icon,Runnable action){
        TextView b=Ui.button(this,label,0,false,()->{action.run();scheduleHide();});b.setTextSize(12);b.setGravity(Gravity.CENTER);b.setPadding(dp(6),dp(8),dp(6),dp(8));Ui.icon(this,b,icon);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(label.equals("Качество и озвучка")?136:84),dp(76));lp.rightMargin=dp(8);row.addView(b,lp);b.setOnFocusChangeListener((v,focused)->{if(focused)scheduleHide();});return b;
    }
    private void showControls(){if(overlay!=null){boolean hidden=overlay.getVisibility()!=View.VISIBLE;overlay.setVisibility(View.VISIBLE);if(hidden&&pauseButton!=null)pauseButton.requestFocus();scheduleHide();}}
    private void hideControls(){if(overlay!=null)overlay.setVisibility(View.GONE);root.requestFocus();}
    private void scheduleHide(){handler.removeCallbacks(hide);if(player!=null&&player.isPlaying()&&panel==null)handler.postDelayed(hide,5000);}
    private void toggle(){if(player==null)return;if(player.isPlaying())player.pause();else player.play();showControls();}
    private void seekBy(long delta){
        if(player==null||!player.isCurrentMediaItemSeekable())return;player.seekTo(Logic.seekTarget(player.getCurrentPosition(),player.getDuration(),delta));showControls();timeline.requestFocus();
    }
    private void showPanel(String name,List<String> labels,List<Runnable> actions,int selected){
        if(closed)return;if(panel!=null)panel.dismiss();handler.removeCallbacks(hide);
        Dialog dialog=new Dialog(this);panel=dialog;
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(24),dp(24),dp(24),dp(16));layout.setBackground(Ui.shape(this,Ui.PANEL,0xff3d424b,1,14));
        TextView heading=Ui.text(this,name,23,Ui.TEXT);heading.setMaxLines(4);heading.setPadding(0,0,0,dp(16));layout.addView(heading);
        ScrollView scroll=new ScrollView(this);LinearLayout choices=new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);scroll.addView(choices);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView focus=null;
        for(int i=0;i<labels.size();i++){
            final int index=i;TextView b=Ui.button(this,labels.get(i),i==selected?R.drawable.ic_check:0,false,()->{dialog.dismiss();actions.get(index).run();});b.setTextSize(15);if(i==selected)b.setTextColor(Ui.ACCENT);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);choices.addView(b,lp);if(focus==null||i==selected)focus=b;
        }
        TextView cancel=Ui.button(this,"Закрыть",R.drawable.ic_back,false,dialog::dismiss);layout.addView(cancel);
        presentDialog(dialog,layout,focus!=null?focus:cancel,500);
    }
    private void presentDialog(Dialog dialog,LinearLayout layout,View focus,int width){
        dialog.setContentView(layout);dialog.setOnDismissListener(d->{if(panel==dialog)panel=null;scheduleHide();});dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams attributes=window.getAttributes();attributes.dimAmount=.45f;attributes.gravity=Gravity.END|Gravity.CENTER_VERTICAL;window.setAttributes(attributes);window.setLayout(dp(Math.min(width,getResources().getConfiguration().screenWidthDp-48)),(int)(getResources().getDisplayMetrics().heightPixels*.88f));}
        focus.requestFocus();
    }
    private void tracks(int type){
        if(player==null)return;ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();int selected=0;
        labels.add(type==C.TRACK_TYPE_TEXT?"Выключить":"Автоматически");actions.add(()->{if(player!=null)player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,type==C.TRACK_TYPE_TEXT).build());});
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==type)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)){
            final int index=i;Format f=group.getTrackFormat(i);String language=f.language==null?"":new Locale(f.language).getDisplayLanguage(new Locale("ru"));
            String label=f.label!=null?f.label:language.isEmpty()?"Дорожка "+(i+1):language;
            labels.add(label+(f.channelCount>0?" · "+f.channelCount+" кан.":"")+(f.codecs!=null?" · "+f.codecs:""));
            if(group.isTrackSelected(i))selected=labels.size()-1;
            actions.add(()->{if(player!=null)player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(type,false).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),index)).build());});
        }
        if(type==C.TRACK_TYPE_AUDIO){
            LinkedHashSet<String> translations=new LinkedHashSet<>();for(Sources.Candidate candidate:candidates)if(!candidate.translation.isEmpty())translations.add(candidate.translation);
            for(String translation:translations){labels.add(translation);actions.add(()->{if(!translation.equals(desiredTranslation))changePreferences("",translation);});if(translation.equals(desiredTranslation))selected=labels.size()-1;}
        }
        showPanel(type==C.TRACK_TYPE_AUDIO?"Озвучка":"Субтитры",labels,actions,selected);
    }
    private void speed(){
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();int selected=0;float[] speeds={.75f,1,1.25f,1.5f,2};
        for(int i=0;i<speeds.length;i++){float speed=speeds[i];labels.add(speed==1?"Обычная · 1×":speed+"×");actions.add(()->{playbackSpeed=speed;if(player!=null)player.setPlaybackSpeed(speed);});if(player!=null&&Math.abs(player.getPlaybackParameters().speed-speed)<.01)selected=i;}
        showPanel("Скорость",labels,actions,selected);
    }
    private void resize(){
        ArrayList<String> labels=new ArrayList<>(Arrays.asList("По размеру экрана","Заполнить с обрезкой","Растянуть"));ArrayList<Runnable> actions=new ArrayList<>();int[] modes={AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_ZOOM,AspectRatioFrameLayout.RESIZE_MODE_FILL};
        for(int i=0;i<modes.length;i++){final int index=i;actions.add(()->{resizeIndex=index;if(video!=null)video.setResizeMode(modes[index]);});}showPanel("Размер изображения",labels,actions,resizeIndex);
    }
    private void preserveCurrent(){
        if(player!=null){
            pendingPosition=player.getCurrentPosition();
            if(fileIndex>=0&&getIntent().getBooleanExtra("serial",false)){
                wantedEpisode=Logic.episodeKey(files.get(fileIndex).optString("path"));
                wantedFilename=wantedEpisode.isEmpty()?fileName(files.get(fileIndex)):"";
            }
        }
    }
    private void changePreferences(String resolution,String translation){
        getSharedPreferences("playerSettings",0).edit().putString("quality",resolution).apply();
        preserveCurrent();desiredQuality=resolution;desiredTranslation=translation;attempted.clear();attempts=0;tryNextSource();
    }
    private void showSources(){
        if(candidates.isEmpty()){releasePlayer();loading("Подготавливаем просмотр");loadSources();return;}
        if(panel!=null)panel.dismiss();handler.removeCallbacks(hide);
        Dialog dialog=new Dialog(this);panel=dialog;
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(24),dp(24),dp(24),dp(16));layout.setBackground(Ui.shape(this,Ui.PANEL,0xff3d424b,1,14));
        TextView heading=Ui.text(this,"Качество и озвучка",23,Ui.TEXT);heading.setPadding(0,0,0,dp(18));layout.addView(heading);
        ArrayList<String> tiers=new ArrayList<>();tiers.add("");
        for(String tier:new String[]{"4K","2K","1080p","720p","576p","480p","360p","240p"}){
            for(Sources.Candidate candidate:candidates)if(candidate.quality.equals(tier)){tiers.add(tier);break;}
        }
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=new LinearLayout(this);strip.addView(tabs);layout.addView(strip,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView hint=Ui.text(this,"Выберите озвучку для этого качества",13,Ui.MUTED);hint.setPadding(0,dp(16),0,dp(14));layout.addView(hint);
        ScrollView scroll=new ScrollView(this);LinearLayout voices=new LinearLayout(this);voices.setOrientation(LinearLayout.VERTICAL);scroll.addView(voices);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        String[] selected={tiers.contains(desiredQuality)?desiredQuality:""};
        ArrayList<TextView> buttons=new ArrayList<>();
        Runnable render=()->{
            voices.removeAllViews();
            for(int i=0;i<buttons.size();i++){
                boolean active=tiers.get(i).equals(selected[0]);buttons.get(i).setBackground(Ui.background(this,active));buttons.get(i).setTextColor(active?Ui.BG:Ui.TEXT);
            }
            LinkedHashSet<String> translations=new LinkedHashSet<>();translations.add("");
            for(Sources.Candidate candidate:candidates)if(selected[0].isEmpty()||selected[0].equals(candidate.quality))if(!candidate.translation.isEmpty())translations.add(candidate.translation);
            for(String translation:translations){
                final String resolution=selected[0];boolean active=desiredQuality.equals(resolution)&&desiredTranslation.equals(translation);
                TextView choice=Ui.button(this,translation.isEmpty()?"Автоматически · предпочитать русский":translation,active?R.drawable.ic_check:R.drawable.ic_audio,false,()->{
                    dialog.dismiss();if(player==null||!desiredQuality.equals(resolution)||!desiredTranslation.equals(translation))changePreferences(resolution,translation);
                });if(active)choice.setTextColor(Ui.ACCENT);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);voices.addView(choice,lp);
            }
            // These tracks are verified in the currently playing video, unlike catalog descriptions.
            if(player!=null&&(selected[0].isEmpty()||selected[0].equals(quality))){
                TextView label=Ui.text(this,"Звуковые дорожки текущего видео",13,Ui.MUTED);label.setPadding(0,dp(16),0,dp(12));voices.addView(label);
                for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)){
                    final String resolution=selected[0];
                    final int index=i;Format f=group.getTrackFormat(i);String language=f.language==null?"":new Locale(f.language).getDisplayLanguage(new Locale("ru"));
                    String name=f.label!=null?f.label:language.isEmpty()?"Дорожка "+(i+1):language;
                    TextView track=Ui.button(this,name+(f.channelCount>0?" · "+f.channelCount+" кан.":""),group.isTrackSelected(i)?R.drawable.ic_check:R.drawable.ic_audio,false,()->{
                        dialog.dismiss();desiredQuality=resolution;getSharedPreferences("playerSettings",0).edit().putString("quality",resolution).apply();if(f.language!=null)preferredAudioLanguage=f.language;
                        if(player!=null)player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(preferredAudioLanguage).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),index)).build());
                    });LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);voices.addView(track,lp);
                }
            }
            scroll.scrollTo(0,0);
        };
        for(String tier:tiers){
            TextView tab=Ui.button(this,tier.isEmpty()?"Авто":tier,0,false,()->{selected[0]=tier;render.run();});tab.setGravity(Gravity.CENTER);tab.setTextSize(14);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(94),dp(48));lp.rightMargin=dp(8);tabs.addView(tab,lp);buttons.add(tab);
        }
        render.run();TextView cancel=Ui.button(this,"Закрыть",R.drawable.ic_back,false,dialog::dismiss);layout.addView(cancel);
        TextView focus=buttons.get(tiers.indexOf(selected[0]));presentDialog(dialog,layout,focus,700);
        strip.post(()->strip.smoothScrollTo(Math.max(0,focus.getLeft()-dp(20)),0));
    }
    private void fail(String message){
        if(closed)return;
        ArrayList<String> labels=new ArrayList<>(Arrays.asList("Попробовать снова","Изменить качество","Вернуться к фильму"));ArrayList<Runnable> actions=new ArrayList<>();
        actions.add(()->{attempted.clear();attempts=0;if(candidates.isEmpty()){loading("Подготавливаем просмотр");loadSources();}else tryNextSource();});actions.add(this::showSources);actions.add(this::finish);
        showPanel(message==null?"Просмотр недоступен":message,labels,actions,-1);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(event.getAction()==KeyEvent.ACTION_DOWN&&panel==null&&player!=null){
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){toggle();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY){player.play();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){player.pause();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_REWIND){seekBy(-10000);return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){seekBy(10000);return true;}
            if(overlay!=null&&overlay.getVisibility()!=View.VISIBLE){
                if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){seekBy(key==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000);return true;}
                if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){showControls();pauseButton.requestFocus();return true;}
            }
            scheduleHide();
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(panel!=null){panel.dismiss();return;}if(overlay!=null&&overlay.getVisibility()==View.VISIBLE){hideControls();return;}super.onBackPressed();}
    @Override public void onStop(){super.onStop();cleanup();if(!isFinishing())finish();}
    private void cleanup(){if(closed)return;closed=true;generation++;if(panel!=null)panel.dismiss();releasePlayer();worker.shutdownNow();handler.removeCallbacksAndMessages(null);stopService(new Intent(this,TorrentService.class));}
    @Override public void onDestroy(){cleanup();super.onDestroy();}
}

package app.cinemahall.tv;

import android.app.*;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public final class PlayerActivity extends Activity {
    private ExoPlayer player;
    private long position;
    private boolean playWhenReady=true;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(state!=null){position=state.getLong("position");playWhenReady=state.getBoolean("playing",true);}
    }
    @androidx.media3.common.util.UnstableApi
    @Override protected void onStart(){
        super.onStart();String url=getIntent().getStringExtra("url");
        if(url==null||!url.startsWith(Net.ENGINE+"/stream/video?")){finish();return;}
        player=new ExoPlayer.Builder(this).build();PlayerView view=new PlayerView(this);
        view.setPlayer(player);view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);setContentView(view);view.requestFocus();
        player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException error){
            new AlertDialog.Builder(PlayerActivity.this).setTitle("Видео недоступно")
                .setMessage("Попробуйте повторить или вернитесь и выберите другой источник.")
                .setPositiveButton("Повторить",(d,n)->{player.prepare();player.play();})
                .setNegativeButton("Назад",(d,n)->finish()).show();
        }});
        player.setMediaItem(MediaItem.fromUri(url));player.seekTo(position);player.prepare();player.setPlayWhenReady(playWhenReady);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putLong("position",player==null?position:player.getCurrentPosition());
        state.putBoolean("playing",player==null?playWhenReady:player.getPlayWhenReady());
    }
    @Override protected void onStop(){
        if(player!=null){position=player.getCurrentPosition();playWhenReady=player.getPlayWhenReady();player.release();player=null;}super.onStop();
    }
}

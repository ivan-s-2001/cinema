package app.cinemahall.nativeui;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public final class PlayerActivity extends Activity {
    private VideoView video;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar loading = new ProgressBar(this);
        FrameLayout.LayoutParams progress = new FrameLayout.LayoutParams(72, 72, Gravity.CENTER);
        root.addView(loading, progress);
        TextView status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setPadding(24, 24, 24, 24);
        status.setText(getIntent().getStringExtra("title"));
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);

        String url = getIntent().getStringExtra("url");
        // Only allow our app-private playback relay; never accept arbitrary URLs via an Intent.
        if (url == null || !url.matches("http://127\\.0\\.0\\.1:1899[12]/play/.*")) {
            status.setText("Некорректный источник");
            loading.setVisibility(View.GONE);
            return;
        }
        MediaController controls = new MediaController(this);
        video.setMediaController(controls);
        video.setOnPreparedListener(player -> {
            loading.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            video.start();
            controls.show(3500);
        });
        video.setOnErrorListener((player, what, extra) -> {
            loading.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            status.setText("Формат не поддерживается системным декодером или источник недоступен. Нажми «Назад» и выбери другой.");
            return true;
        });
        video.setVideoURI(Uri.parse(url));
        video.requestFocus();
    }
    @Override protected void onPause() {
        if (video != null) video.pause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}

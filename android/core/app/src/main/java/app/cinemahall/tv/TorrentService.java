package app.cinemahall.tv;
import app.cinemahall.tv.R;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import java.io.File;
import org.json.JSONObject;

public final class TorrentService extends Service {
    private Process process;
    static volatile String failure;
    static volatile boolean ready;
    private volatile boolean stopping;
    @Override public void onCreate() {
        super.onCreate(); failure = null; ready = false;
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("playback", "Воспроизведение", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, TorrentService.class).setAction("stop");
        PendingIntent action = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "playback").setSmallIcon(R.drawable.ch_engine_logo)
            .setContentTitle("CinemaHall").setContentText("Подготовка и воспроизведение видео")
            .setOngoing(true).addAction(new Notification.Action.Builder(null, "Остановить", action).build()).build());
        new Thread(this::launch, "torrserver-launch").start();
    }
    private void launch() {
        try {
            File data = new File(getFilesDir(), "torrserver"); data.mkdirs();
            File executable = new File(getApplicationInfo().nativeLibraryDir, "libtorrserver.so");
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-p", "18990", "-i", "127.0.0.1", "-d", data.toString());
            builder.redirectErrorStream(true).redirectOutput(new File(getCacheDir(), "torrserver.log"));
            builder.environment().put("GOMEMLIMIT", "192MiB");
            synchronized (this) {
                if (stopping) return;
                process = builder.start();
            }
            boolean connected = false;
            for (int i = 0; i < 60 && !stopping; i++) {
                if (!process.isAlive()) throw new Exception("TorrServer завершился. Проверьте совместимость устройства.");
                try { Net.request(Net.ENGINE + "/echo", null, 1024); connected = true; break; }
                catch (Exception e) { Thread.sleep(250); }
            }
            if (stopping) return;
            if (!connected) throw new Exception("TorrServer не запустился за отведённое время");
            JSONObject sets = Net.action("/settings", "get");
            sets.put("CacheSize", 96 * 1024 * 1024).put("PreloadCache", 25).put("ReaderReadAHead", 75)
                .put("ConnectionsLimit", 25).put("UseDisk", false).put("EnableDLNA", false)
                .put("EnableBonjour", false).put("EnableRutorSearch", false).put("EnableTorznabSearch", false);
            Net.json(Net.ENGINE + "/settings", new JSONObject().put("action", "set").put("sets", sets));
            ready = true;
            int exit = process.waitFor();
            if (!stopping) { failure = "TorrServer остановился (код " + exit + ")"; stopSelf(); }
        } catch (Exception e) { if (!stopping) { failure = e.getMessage(); stopSelf(); } }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public synchronized void onDestroy() {
        stopping = true; ready = false;
        if (process != null) { process.destroy(); process.destroyForcibly(); }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

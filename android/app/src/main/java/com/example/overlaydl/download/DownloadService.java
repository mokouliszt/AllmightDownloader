package com.example.overlaydl.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * yt-dlp（youtubedl-android）でダウンロードを実行し、通知バーに進捗を表示するフォアグラウンドサービス。
 * 完了後は Android 10+ では MediaStore 経由で Download/ に保存する。
 */
public class DownloadService extends Service {

    public static final String ACTION_DOWNLOAD = "com.example.overlaydl.DOWNLOAD";
    public static final String ACTION_CANCEL = "com.example.overlaydl.CANCEL";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_MODE = "mode";   // "MP4" or "MP3"
    public static final String EXTRA_PROCESS_ID = "pid";

    private static final String CHANNEL_ID = "downloads";
    private static final int FGS_NOTIF_ID = 2000;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger active = new AtomicInteger(0);
    private final AtomicInteger notifSeq = new AtomicInteger(2001);
    private NotificationManager nm;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ダウンロード", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopIfIdle(); return START_NOT_STICKY; }
        String action = intent.getAction();

        if (ACTION_CANCEL.equals(action)) {
            String pid = intent.getStringExtra(EXTRA_PROCESS_ID);
            if (pid != null) {
                try { YoutubeDL.getInstance().destroyProcessById(pid); } catch (Exception ignored) {}
            }
            return START_NOT_STICKY;
        }

        final String url = intent.getStringExtra(EXTRA_URL);
        final String mode = intent.getStringExtra(EXTRA_MODE) == null ? "MP4" : intent.getStringExtra(EXTRA_MODE);
        if (url == null || url.trim().isEmpty()) { stopIfIdle(); return START_NOT_STICKY; }

        final int notifId = notifSeq.getAndIncrement();
        final String processId = "dl-" + notifId;

        startForegroundOngoing();
        active.incrementAndGet();
        executor.submit(() -> runDownload(url.trim(), mode, notifId, processId));
        return START_NOT_STICKY;
    }

    private void runDownload(String url, String mode, int notifId, String processId) {
        boolean mp3 = "MP3".equalsIgnoreCase(mode);
        File jobDir = null;
        try {
            notifyProgress(notifId, processId, mode, 0, -1, "準備中…");
            Ytdl.ensureInit(getApplicationContext());

            File tmpRoot = new File(getExternalFilesDir(null), "dl_tmp");
            jobDir = new File(tmpRoot, processId);
            //noinspection ResultOfMethodCallIgnored
            jobDir.mkdirs();

            YoutubeDLRequest request = new YoutubeDLRequest(url);
            request.addOption("-o", jobDir.getAbsolutePath() + "/%(title).80B.%(ext)s");
            request.addOption("--no-playlist");
            request.addOption("--no-mtime");
            request.addOption("--restrict-filenames");
            // 認証が要るサイト（X 等）向け: files ディレクトリに cookies.txt があれば使う
            File cookies = new File(getExternalFilesDir(null), "cookies.txt");
            if (cookies.exists()) {
                request.addOption("--cookies", cookies.getAbsolutePath());
            }
            // ネットワーク耐性（X 等の JSON 取得タイムアウト対策）
            request.addOption("--socket-timeout", "30");
            request.addOption("--retries", "10");
            request.addOption("--extractor-retries", "5");
            request.addOption("--fragment-retries", "10");
            if (mp3) {
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
                request.addOption("--audio-quality", "0");
            } else {
                request.addOption("-f", "bestvideo*+bestaudio/best");
                request.addOption("--merge-output-format", "mp4");
            }

            final int fNotifId = notifId;
            final String fProcessId = processId;
            final String fMode = mode;
            YoutubeDL.getInstance().execute(request, processId,
                    new Function3<Float, Long, String, Unit>() {
                        @Override
                        public Unit invoke(Float progress, Long etaInSeconds, String line) {
                            int p = (progress == null || progress < 0) ? 0 : (int) (float) progress;
                            long eta = (etaInSeconds == null) ? -1 : etaInSeconds;
                            if (p >= 100) {
                                // 各ストリームDL完了後の結合/変換フェーズ（進捗が出ない）
                                notifyProgress(fNotifId, fProcessId, fMode, -1, -1, "処理中…（変換・結合）");
                            } else {
                                notifyProgress(fNotifId, fProcessId, fMode, p, eta, null);
                            }
                            return Unit.INSTANCE;
                        }
                    });

            File out = newestFile(jobDir);
            String savedTo = (out != null) ? exportToDownloads(out, mp3) : null;
            notifyDone(notifId, mode, savedTo, out != null ? out.getName() : url);
        } catch (Throwable t) {
            notifyError(notifId, mode, concise(t));
        } finally {
            if (jobDir != null) deleteRecursive(jobDir);
            if (active.decrementAndGet() <= 0) {
                stopForegroundCompat();
                stopSelf();
            }
        }
    }

    // ---------- 保存（Download/ へ） ----------
    private String exportToDownloads(File src, boolean mp3) {
        String mime = mp3 ? "audio/mpeg" : "video/mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver r = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return src.getAbsolutePath();
            try (InputStream in = new FileInputStream(src); OutputStream os = r.openOutputStream(uri)) {
                copy(in, os);
            } catch (Exception e) {
                return src.getAbsolutePath();
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            r.update(uri, cv, null, null);
            return "Download/" + src.getName();
        } else {
            File pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dst = new File(pubDir, src.getName());
            try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
                copy(in, os);
            } catch (Exception e) {
                return src.getAbsolutePath();
            }
            return dst.getAbsolutePath();
        }
    }

    private static void copy(InputStream in, OutputStream os) throws Exception {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    @Nullable
    private static File newestFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (f.isDirectory()) {
                File inner = newestFile(f);
                if (inner != null && (best == null || inner.lastModified() > best.lastModified())) best = inner;
                continue;
            }
            String name = f.getName();
            if (name.endsWith(".part") || name.endsWith(".ytdl")) continue; // 一時ファイル除外
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------- 通知 ----------
    private void startForegroundOngoing() {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("ダウンロード")
                .setContentText("実行中…")
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FGS_NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FGS_NOTIF_ID, b.build());
        }
    }

    private void notifyProgress(int notifId, String processId, String mode, int progress, long eta, String overrideText) {
        String text = overrideText != null ? overrideText
                : (progress > 0
                    ? progress + "%" + (eta > 0 ? "  (残り約" + eta + "秒)" : "")
                    : "処理中…");
        Intent cancel = new Intent(this, DownloadService.class);
        cancel.setAction(ACTION_CANCEL);
        cancel.putExtra(EXTRA_PROCESS_ID, processId);
        PendingIntent pi = PendingIntent.getService(this, notifId, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(mode + " ダウンロード")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(progress, 0), progress <= 0)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "キャンセル", pi);
        nm.notify(notifId, b.build());
    }

    private void notifyDone(int notifId, String mode, String savedTo, String name) {
        String text = savedTo != null ? "保存先: " + savedTo : "完了: " + name;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(mode + " 保存完了")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(false)
                .setAutoCancel(true);
        nm.notify(notifId, b.build());
    }

    private void notifyError(int notifId, String mode, String msg) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(mode + " 失敗")
                .setContentText(msg)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
                .setOngoing(false)
                .setAutoCancel(true);
        nm.notify(notifId, b.build());
    }

    /** yt-dlp の長い警告を除き、ERROR 行（なければ最終行）だけ取り出す */
    private static String concise(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return t.getClass().getSimpleName();
        String[] lines = msg.split("\\r?\\n");
        String err = null;
        for (String ln : lines) {
            String s = ln.trim();
            if (s.startsWith("ERROR:")) err = s;
        }
        if (err != null) return err;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        }
        return msg;
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private void stopIfIdle() {
        if (active.get() <= 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}

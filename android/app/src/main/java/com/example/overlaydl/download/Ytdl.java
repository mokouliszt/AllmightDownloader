package com.example.overlaydl.download;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

/** youtubedl-android の初期化と yt-dlp 自己更新を集約するヘルパー。 */
public final class Ytdl {

    private static volatile boolean initialized = false;

    private Ytdl() {}

    public static synchronized void ensureInit(Context ctx) throws Exception {
        if (initialized) return;
        YoutubeDL.getInstance().init(ctx);
        FFmpeg.getInstance().init(ctx);
        initialized = true;
    }

    /** 同梱 yt-dlp を最新の STABLE に更新。結果文字列を返す。 */
    public static synchronized String update(Context ctx) throws Exception {
        ensureInit(ctx);
        YoutubeDL.UpdateStatus status =
                YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE);
        String ver;
        try {
            ver = YoutubeDL.getInstance().version(ctx);
        } catch (Throwable t) {
            ver = "?";
        }
        return String.valueOf(status) + " (yt-dlp " + ver + ")";
    }
}

package com.example.overlaydl.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.example.overlaydl.download.Ytdl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

@CapacitorPlugin(name = "Overlay")
public class OverlayPlugin extends Plugin {

    private File cookiesFile() {
        return new File(getContext().getExternalFilesDir(null), "cookies.txt");
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(getContext());
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", canDraw());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (!canDraw()) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!canDraw()) {
            call.reject("overlay permission not granted");
            return;
        }
        Intent intent = new Intent(getContext(), OverlayService.class);
        intent.setAction(OverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), OverlayService.class);
        intent.setAction(OverlayService.ACTION_STOP);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", OverlayService.RUNNING);
        call.resolve(ret);
    }

    @PluginMethod
    public void update(PluginCall call) {
        new Thread(() -> {
            try {
                String result = Ytdl.update(getContext());
                JSObject ret = new JSObject();
                ret.put("result", result);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("update failed: " + e.getMessage());
            }
        }).start();
    }

    // ---------- cookies.txt インポート ----------
    @PluginMethod
    public void hasCookies(PluginCall call) {
        File f = cookiesFile();
        JSObject ret = new JSObject();
        ret.put("present", f.exists() && f.length() > 0);
        call.resolve(ret);
    }

    @PluginMethod
    public void clearCookies(PluginCall call) {
        File f = cookiesFile();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        call.resolve();
    }

    @PluginMethod
    public void importCookies(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // .txt が text/plain でも octet-stream でも拾えるように
        startActivityForResult(call, intent, "onCookiesPicked");
    }

    @ActivityCallback
    private void onCookiesPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("canceled");
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) {
            call.reject("no file selected");
            return;
        }
        File dest = cookiesFile();
        try (InputStream in = getContext().getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(dest)) {
            if (in == null) { call.reject("cannot open file"); return; }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("path", dest.getAbsolutePath());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("copy failed: " + e.getMessage());
        }
    }
}

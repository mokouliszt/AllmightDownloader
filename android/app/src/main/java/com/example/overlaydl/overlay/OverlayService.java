package com.example.overlaydl.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.overlaydl.download.DownloadService;

/**
 * 左上に赤丸の +/- 浮遊ボタンを重ねるフォアグラウンドサービス。
 * + タップで画面中央にURL入力パネル（周囲は薄暗いスクリム）を表示し、ボタンは - に変化。
 * ボタンをドラッグすると下部中央にゴミ箱（赤い輪郭・塗りなし）が出て、そこにドロップで終了。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.example.overlaydl.START";
    public static final String ACTION_STOP = "com.example.overlaydl.STOP";
    private static final String CHANNEL_ID = "overlay_channel";
    private static final int NOTIF_ID = 1001;

    private WindowManager windowManager;
    private View bubbleView;                       // 浮遊する +/- ボタン
    private TextView bubbleLabel;
    private WindowManager.LayoutParams bubbleParams;
    private View panelView;                         // スクリム + 中央カード
    private View trashView;                         // ドラッグ中のゴミ箱ターゲット
    private EditText urlInput;
    private boolean expanded = false;

    /** サービス稼働状態（同一プロセスのプラグインから参照して停止ボタンと同期する） */
    public static volatile boolean RUNNING = false;

    private static final int TRASH_BOX_DP = 64;
    private static final int TRASH_MARGIN_TOP_DP = 64;   // 画面上からの距離（＋ボタンy=80dpより少し高い）
    private static final String TRASH_COLOR = "#C62828"; // ゴミ箱の色（濃いめの赤）

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopOverlay();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        showBubble();
        RUNNING = true;
        return START_STICKY;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ---------- フォアグラウンド通知 ----------
    private void startForegroundCompat() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notif = builder
                .setContentTitle("Overlay Downloader")
                .setContentText("オーバーレイ実行中")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    // ---------- 浮遊ボタン ----------
    private void showBubble() {
        if (bubbleView != null) return;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        TextView label = new TextView(this);
        label.setText("+");
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        label.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#E53935")); // 赤
        label.setBackground(circle);
        bubbleLabel = label;

        FrameLayout container = new FrameLayout(this);
        int size = dp(56);
        container.addView(label, new FrameLayout.LayoutParams(size, size));

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START; // 左上基準
        bubbleParams.x = dp(12);
        bubbleParams.y = dp(80);

        container.setOnTouchListener(new View.OnTouchListener() {
            int initialX, initialY;
            float touchX, touchY;
            boolean moved;
            boolean overTrash;
            final int slop = dp(8);

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        overTrash = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                            showTrash(); // ドラッグ開始でゴミ箱表示
                        }
                        if (moved) {
                            bubbleParams.x = initialX + dx;
                            bubbleParams.y = initialY + dy; // TOP gravity: 下方向が +
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                            boolean now = isOverTrash(e.getRawX(), e.getRawY());
                            if (now != overTrash) {
                                overTrash = now;
                                highlightTrash(now);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved && overTrash) {
                            stopOverlay();             // ゴミ箱にドロップ → 終了
                            return true;
                        }
                        removeTrash();
                        if (!moved) togglePanel();     // タップ → パネル開閉
                        return true;
                }
                return false;
            }
        });

        bubbleView = container;
        windowManager.addView(container, bubbleParams);
    }

    private void removeBubble() {
        if (bubbleView != null && windowManager != null) {
            windowManager.removeView(bubbleView);
            bubbleView = null;
        }
    }

    /** スクリムより前面に +/- ボタンを出す（再追加で最前面化） */
    private void bringBubbleToFront() {
        if (bubbleView != null && windowManager != null) {
            windowManager.removeView(bubbleView);
            windowManager.addView(bubbleView, bubbleParams);
        }
    }

    // ---------- ゴミ箱ターゲット ----------
    private void showTrash() {
        if (trashView != null || windowManager == null) return;

        FrameLayout wrap = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);                  // 塗りつぶしなし
        ring.setStroke(dp(2), Color.parseColor(TRASH_COLOR)); // 濃い赤の輪郭
        wrap.setBackground(ring);

        int isize = dp(30);
        TrashIconView icon = new TrashIconView(this, Color.parseColor(TRASH_COLOR), dp(2)); // 枠線と同じ単色・同じ線幅
        wrap.addView(icon, new FrameLayout.LayoutParams(isize, isize, Gravity.CENTER));
        wrap.setAlpha(1f);

        int box = dp(TRASH_BOX_DP);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                box, box, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // 触れない＝ドラッグ判定はボタン側で実施
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(TRASH_MARGIN_TOP_DP); // ＋ボタンより少し上の中央
        trashView = wrap;
        windowManager.addView(wrap, lp);
    }

    private void highlightTrash(boolean on) {
        if (trashView != null) {
            trashView.setScaleX(on ? 1.35f : 1f);
            trashView.setScaleY(on ? 1.35f : 1f);
        }
    }

    private void removeTrash() {
        if (trashView != null && windowManager != null) {
            windowManager.removeView(trashView);
            trashView = null;
        }
    }

    /** ゴミ箱中心と指の距離で判定（実画面サイズ基準） */
    private boolean isOverTrash(float rawX, float rawY) {
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        float cx = dm.widthPixels / 2f;
        float cy = dp(TRASH_MARGIN_TOP_DP) + dp(TRASH_BOX_DP) / 2f;
        return Math.hypot(rawX - cx, rawY - cy) < dp(64);
    }

    // ---------- 中央パネル開閉 ----------
    private void togglePanel() {
        if (expanded) collapsePanel(); else expandPanel();
    }

    private void expandPanel() {
        if (panelView != null) return;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#99000000")); // 薄暗いスクリム
        root.setOnClickListener(v -> collapsePanel());          // 暗部タップで閉じる
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                collapsePanel();
                return true;
            }
            return false;
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);

        TextView lbl = new TextView(this);
        lbl.setText("動画URL");
        lbl.setTextColor(Color.parseColor("#666666"));
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(lbl);

        urlInput = new EditText(this);
        urlInput.setHint("https://...");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        urlInput.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = dp(6);
        card.addView(urlInput, inLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button mp4 = makeButton("MP4 (HD) 保存", true);
        Button mp3 = makeButton("MP3 保存", false);
        mp4.setOnClickListener(v -> onSave("MP4"));
        mp3.setOnClickListener(v -> onSave("MP3"));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b2.leftMargin = dp(10);
        row.addView(mp4, b1);
        row.addView(mp3, b2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(16);
        card.addView(row, rowLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        root.addView(card, cardLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // focusable（IME用）
                PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;

        panelView = root;
        windowManager.addView(root, lp);
        root.requestFocus();
        bringBubbleToFront();
        expanded = true;
        if (bubbleLabel != null) bubbleLabel.setText("−");
    }

    private Button makeButton(String text, boolean filled) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (filled) {
            bg.setColor(Color.parseColor("#E53935"));
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Color.parseColor("#E53935"));
            b.setTextColor(Color.parseColor("#E53935"));
        }
        b.setBackground(bg);
        return b;
    }

    /** MP4/MP3 ボタン → DownloadService を起動して yt-dlp ダウンロード開始 */
    private void onSave(String mode) {
        String url = urlInput != null ? urlInput.getText().toString().trim() : "";
        if (url.isEmpty()) {
            Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_DOWNLOAD);
        i.putExtra(DownloadService.EXTRA_URL, url);
        i.putExtra(DownloadService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this, mode + " ダウンロードを開始しました", Toast.LENGTH_SHORT).show();
        collapsePanel();
    }

    private void collapsePanel() {
        hideKeyboard();
        if (panelView != null && windowManager != null) {
            windowManager.removeView(panelView);
            panelView = null;
        }
        expanded = false;
        if (bubbleLabel != null) bubbleLabel.setText("+");
    }

    private void hideKeyboard() {
        if (urlInput != null) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        }
    }

    /** 全オーバーレイを片付けてサービス終了 */
    private void stopOverlay() {
        RUNNING = false;
        collapsePanel();
        removeTrash();
        removeBubble();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        collapsePanel();
        removeTrash();
        removeBubble();
        super.onDestroy();
    }

    /** 枠線と同じ単色・同じ線幅で描くゴミ箱アイコン（完全不透明＝濃さが一致） */
    private static class TrashIconView extends View {
        private final Paint p;

        TrashIconView(Context ctx, int color, float strokePx) {
            super(ctx);
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokePx);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float pad = Math.min(w, h) * 0.18f;
            float left = pad, right = w - pad, top = pad, bottom = h - pad;
            float cx = w / 2f;
            float lidY = top + (bottom - top) * 0.20f;
            float handleHalf = (right - left) * 0.16f;

            // フタの取っ手
            c.drawLine(cx - handleHalf, top, cx + handleHalf, top, p);
            c.drawLine(cx - handleHalf, top, cx - handleHalf, lidY, p);
            c.drawLine(cx + handleHalf, top, cx + handleHalf, lidY, p);
            // フタ（横バー）
            c.drawLine(left, lidY, right, lidY, p);
            // 本体（下すぼまり）
            float btX = (right - left) * 0.10f;
            float bbX = (right - left) * 0.17f;
            float bodyTop = lidY + (bottom - lidY) * 0.06f;
            c.drawLine(left + btX, bodyTop, left + bbX, bottom, p);
            c.drawLine(right - btX, bodyTop, right - bbX, bottom, p);
            c.drawLine(left + bbX, bottom, right - bbX, bottom, p);
            // 縦リブ
            float ribTop = bodyTop + (bottom - bodyTop) * 0.20f;
            float ribBot = bottom - (bottom - bodyTop) * 0.14f;
            float ribDx = (right - left) * 0.14f;
            c.drawLine(cx, ribTop, cx, ribBot, p);
            c.drawLine(cx - ribDx, ribTop, cx - ribDx, ribBot, p);
            c.drawLine(cx + ribDx, ribTop, cx + ribDx, ribBot, p);
        }
    }
}

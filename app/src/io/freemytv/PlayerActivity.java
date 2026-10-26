package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen player for the open channels (HLS through Android's own player). Up/Down (or Channel +/−) change
 * channel within the section, OK shows the channel card again, Back returns to the home screen.
 */
public class PlayerActivity extends Activity {
    static final String GROUP = "group", INDEX = "index";
    private static final long INFO_MS = 4500, CLOCK_WAIT_MS = 8000;

    static void open(Context c, String group, int index) {
        c.startActivity(new Intent(c, PlayerActivity.class).putExtra(GROUP, group).putExtra(INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float k;
    private List<Channels.Channel> list;
    private int index;
    private VideoView video;
    private View curtain;
    private LinearLayout info;
    private ImageView logo;
    private TextView name, detail, status;
    private long startedAt;

    private final Runnable hideInfo = () -> info.animate().alpha(0).setDuration(400).start();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        list = Channels.section(this, getIntent().getStringExtra(GROUP));
        index = Math.max(0, Math.min(list.size() - 1, getIntent().getIntExtra(INDEX, 0)));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        video.setFocusable(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // Covers the last frame of the previous channel (the player leaves it frozen) until the new one shows.
        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, new FrameLayout.LayoutParams(-1, -1));

        status = text(Ui.medium, 30, 0xFFE5E5EA);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));

        // Channel card, bottom left, like the home screen's cards.
        info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6F2F2F7);
        bg.setCornerRadius(px(28));
        info.setBackground(bg);
        info.setPadding(px(22), px(22), px(40), px(22));
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setCornerRadius(px(16));
        logo.setBackground(logoBg);
        logo.setPadding(px(14), px(10), px(14), px(10));
        info.addView(logo, new LinearLayout.LayoutParams(px(160), px(90)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(26), 0, 0, 0);
        name = text(Ui.medium, 36, 0xFF1D1D1F);
        detail = text(Ui.regular, 22, 0xFF6E6E73);
        texts.addView(name);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
        dLp.topMargin = px(8);
        texts.addView(detail, dLp);
        info.addView(texts);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        iLp.setMargins(px(80), 0, 0, px(72));
        root.addView(info, iLp);

        video.setOnPreparedListener(mp -> {
            status.setText("");
            mp.setLooping(false);
            handler.postDelayed(reveal, 1500);   // in case the player never reports its first frame
        });
        video.setOnErrorListener((mp, what, extra) -> {
            AppLog.w("player: " + current().name + " failed (" + what + "/" + extra + ")");
            status.setText(Ui.tr("Este canal não abriu agora.\n▲ ▼ para trocar de canal", "This channel didn't open.\n▲ ▼ to change channel"));
            showInfo();
            return true;
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status.setText(Ui.tr("Carregando…", "Loading…"));
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) status.setText("");
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) reveal.run();
            return false;
        });
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        play();
    }

    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        video.stopPlayback();
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                showInfo();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private Channels.Channel current() {
        return list.get(index);
    }

    private void step(int dir) {
        if (list.isEmpty()) return;
        index = (index + dir + list.size()) % list.size();
        play();
    }

    private void play() {
        if (list.isEmpty()) {
            status.setText(Ui.tr("Nenhum canal", "No channels"));
            return;
        }
        Channels.Channel ch = current();
        Bitmap b = Channels.logo(this, ch);
        logo.setImageBitmap(b);
        logo.setVisibility(b == null ? View.GONE : View.VISIBLE);
        ((GradientDrawable) logo.getBackground()).setColor(b != null && Channels.lightLogo(ch) ? 0xFF2C2C2E : 0xFFFFFFFF);
        name.setText(ch.name);
        detail.setText(Channels.detail(ch) + "   ▲ ▼ " + Ui.tr("trocar canal", "change channel"));
        showInfo();
        status.setText(Ui.tr("Carregando…", "Loading…"));
        handler.removeCallbacks(reveal);
        curtain.animate().cancel();
        curtain.setAlpha(1);
        video.stopPlayback();
        handler.removeCallbacks(start);
        startedAt = System.currentTimeMillis();
        handler.post(start);
    }

    /**
     * Starts the stream once the TV's clock is right again (the home screen's live TV left the antenna's wrong date;
     * the app puts it back in a second or two, and secure streams fail until then).
     */
    private final Runnable start = new Runnable() {
        @Override public void run() {
            boolean waited = System.currentTimeMillis() - startedAt > CLOCK_WAIT_MS;
            if (!Ui.clockOk(System.currentTimeMillis()) && !waited) {
                handler.postDelayed(this, 300);
                return;
            }
            Channels.Channel ch = current();
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", ch.userAgent != null ? ch.userAgent : "Mozilla/5.0 (Linux; Android 9) FreeMyTV");
            if (ch.referrer != null) headers.put("Referer", ch.referrer);
            video.setVideoURI(Uri.parse(ch.url), headers);
            video.start();
        }
    };

    private final Runnable reveal = () -> curtain.animate().alpha(0).setDuration(250).start();

    private void showInfo() {
        handler.removeCallbacks(hideInfo);
        info.animate().cancel();
        info.setAlpha(1);
        handler.postDelayed(hideInfo, INFO_MS);
    }

    private int px(float v) {
        return Math.round(v * k);
    }

    private TextView text(android.graphics.Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(false);
        return t;
    }
}

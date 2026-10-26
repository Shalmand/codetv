package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Free My TV's settings (the app's icon, or "Home Settings" on the home screen): the home screen choice, Free My TV
 * or Original. The switch runs in the main process (HomeMode); this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private static final int BG = 0xFFEBEBF0, INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, RING = 0xFF1D1D1F;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float k;
    private TextView status, notices, freeButton, originalButton;
    private boolean running;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(px(160), px(110), px(160), px(60));

        TextView title = text(Ui.displaySemibold, 56, INK);
        title.setText("Free My TV");
        page.addView(title);
        TextView subtitle = text(Ui.regular, 26, DIM);
        subtitle.setText(Ui.tr("Escolha qual Início a TV usa. \"Original\" devolve tudo como veio de fábrica.",
                "Choose the TV's home screen. \"Original\" brings everything back as it came from the factory."));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = px(16);
        page.addView(subtitle, sLp);

        LinearLayout buttons = new LinearLayout(this);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, px(110));
        bLp.topMargin = px(60);
        page.addView(buttons, bLp);
        freeButton = button(Ui.tr("Início Free My TV", "Free My TV home"));
        freeButton.setOnClickListener(v -> ask(HomeMode.FREE));
        originalButton = button(Ui.tr("Início original", "Original home"));
        originalButton.setOnClickListener(v -> ask(HomeMode.ORIGINAL));
        TextView tvSettings = button(Ui.tr("Ajustes da TV", "TV Settings"));
        tvSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        });
        buttons.addView(freeButton, weight(0));
        buttons.addView(originalButton, weight(1));
        buttons.addView(tvSettings, weight(2));

        status = text(Ui.medium, 28, INK);
        status.setSingleLine(false);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = px(56);
        page.addView(status, stLp);

        notices = text(Ui.regular, 24, DIM);
        notices.setSingleLine(false);
        notices.setLineSpacing(px(10), 1);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
        nLp.topMargin = px(28);
        page.addView(notices, nLp);

        setContentView(page);
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        poll.run();
        String mode = HomeMode.state(this).optString("mode");
        (HomeMode.FREE.equals(mode) ? originalButton : freeButton).requestFocus();
    }

    @Override protected void onPause() {
        running = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void ask(String mode) {
        if (!HomeMode.state(this).optString("busy").isEmpty()) return;
        HomeMode.request(this, mode);
        status.setText(Ui.tr("Começando…", "Starting…"));
    }

    private void refresh() {
        JSONObject s = HomeMode.state(this);
        String busy = s.optString("busy");
        String error = s.optString("error");
        boolean free = HomeMode.FREE.equals(s.optString("mode"));
        if (!busy.isEmpty()) status.setText(busy);
        else if (!error.isEmpty()) status.setText(error);
        else status.setText(Ui.tr("Agora: ", "Now: ") + (free ? Ui.tr("Início Free My TV", "Free My TV home")
                : Ui.tr("Início original", "Original home")));
        freeButton.setSelected(free);
        originalButton.setSelected(!free);
        StringBuilder sb = new StringBuilder();
        JSONArray list = s.optJSONArray("notices");
        if (list != null) {
            for (int i = 0; i < list.length() && i < 4; i++) {
                JSONObject n = list.optJSONObject(i);
                if (n == null) continue;
                sb.append(Ui.clock(n.optLong("at"))).append("  ").append(n.optString("text")).append('\n');
            }
        }
        notices.setText(sb.toString().trim());
    }

    private int px(float v) {
        return Math.round(v * k);
    }

    private LinearLayout.LayoutParams weight(int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (i > 0) lp.leftMargin = px(32);
        return lp;
    }

    private TextView text(android.graphics.Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView button(String label) {
        TextView b = text(Ui.semibold, 30, INK);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(px(24));
        b.setBackground(bg);
        b.setElevation(px(2));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((View v, boolean has) -> {
            bg.setStroke(has ? px(5) : 0, RING);
            v.animate().scaleX(has ? 1.04f : 1).scaleY(has ? 1.04f : 1).translationZ(has ? px(12) : 0).setDuration(150).start();
        });
        return b;
    }
}

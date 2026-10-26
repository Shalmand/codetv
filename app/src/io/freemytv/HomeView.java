package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android 9 home screen: plain Views on a light background, no shaders. From top to bottom: the clock, the live TV
 * card (a TvView already tuned to the channel in config.properties) next to the weather and prices, the app shelf,
 * and the inputs and shortcuts. "All Apps" opens a grid over the screen. Laid out for 1920 × 1080, scaled to the screen.
 */
final class HomeView extends FrameLayout implements HomeData.Listener {
    private static final int BG = 0xFFEBEBF0, CARD = 0xFFFFFFFF, INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, FAINT = 0xFF8E8E93;
    private static final int RING = 0xFF1D1D1F, LIVE_RED = 0xFFFF3B30, UP = 0xFF248A3D, DOWN = 0xFFD70015;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final HomeData data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float k;

    private TextView clock, date;
    private TvView tv;
    private View liveCard;
    private LinearLayout page;
    private TextView liveStatus, liveBadge;
    private String liveInput;
    private Uri liveChannel;
    private boolean tuned;

    private ImageView weatherIcon;
    private TextView weatherCity, weatherTemp, weatherCondition, weatherRange, weatherSun;
    private final List<TextView[]> hourViews = new ArrayList<>();
    private final List<TextView[]> quoteViews = new ArrayList<>();

    private LinearLayout shelfRow, inputsRow;
    private View firstApp;
    private GridView grid;
    private FrameLayout sheet;
    private boolean running, startFocused;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClock();
            data.refreshIfStale();
            handler.postDelayed(this, 30_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        setBackgroundColor(BG);
        build();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        running = true;
        Ui.applyTimeZone();
        data.addListener(this);
        handler.removeCallbacks(tick);
        tick.run();
        onData();
        loadShelf();
        loadInputs();
        // Fetch first: once the channel plays, TCL TVs take the antenna's (wrong) clock and certificates fail.
        handler.removeCallbacks(tuneSoon);
        handler.postDelayed(tuneSoon, 2500);
    }

    private final Runnable tuneSoon = () -> {
        if (running) tuneLive();
    };

    void pause() {
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(tuneSoon);
        data.removeListener(this);
        // Frees the tuner right away: the live TV app (opened from here) needs it.
        if (tv != null) tv.reset();
        if (tuned || !Ui.clockOk(System.currentTimeMillis())) HomeMode.requestClockFix(getContext());
        tuned = false;
    }

    void destroy() {
        pause();
    }

    /** Home pressed while the home screen is showing: grid closed, focus on the first app. */
    void reset(boolean animated) {
        closeSheet();
        if (firstApp != null) firstApp.requestFocus();
    }

    /** Back closes the grid; on the home screen itself it does nothing (there's nowhere to go back to). */
    boolean onKey(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && sheet != null && sheet.getVisibility() == VISIBLE) closeSheet();
            return true;
        }
        return false;
    }

    // MARK: - Layout

    private int px(float v) {
        return Math.round(v * k);
    }

    private void build() {
        noClip(this);
        page = new LinearLayout(getContext());
        noClip(page);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(80), px(48), px(80), px(40));
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Clock and date
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.BOTTOM);
        clock = text(Ui.light, 64, INK);
        date = text(Ui.regular, 26, DIM);
        date.setPadding(px(24), 0, 0, px(12));
        header.addView(clock);
        header.addView(date);
        page.addView(header, new LinearLayout.LayoutParams(-1, px(96)));

        // Live TV + weather and prices
        LinearLayout top = new LinearLayout(getContext());
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(-1, px(400));
        topLp.topMargin = px(20);
        page.addView(top, topLp);
        top.addView(liveCard(), new LinearLayout.LayoutParams(px(711), -1));
        LinearLayout side = new LinearLayout(getContext());
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(0, -1, 1);
        sideLp.leftMargin = px(40);
        top.addView(side, sideLp);
        side.addView(weatherCard(), new LinearLayout.LayoutParams(-1, 0, 1.9f));
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(-1, 0, 1);
        qLp.topMargin = px(24);
        side.addView(quotesCard(), qLp);

        // Apps
        page.addView(label(Ui.tr("Apps", "Apps")), labelParams(px(30)));
        shelfRow = new LinearLayout(getContext());
        noClip(shelfRow);
        page.addView(shelfRow, new LinearLayout.LayoutParams(-1, px(124)));

        // Inputs and shortcuts
        LinearLayout labels = new LinearLayout(getContext());
        labels.addView(label(Ui.tr("Entradas", "Inputs")), new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(label(Ui.tr("Atalhos", "Shortcuts")), new LinearLayout.LayoutParams(px(4 * 176 + 3 * 20), -2));
        page.addView(labels, labelParams(px(30)));
        LinearLayout bottom = new LinearLayout(getContext());
        noClip(bottom);
        page.addView(bottom, new LinearLayout.LayoutParams(-1, px(104)));
        inputsRow = new LinearLayout(getContext());
        noClip(inputsRow);
        bottom.addView(inputsRow, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout shortcuts = new LinearLayout(getContext());
        noClip(shortcuts);
        bottom.addView(shortcuts, new LinearLayout.LayoutParams(px(4 * 176 + 3 * 20), -1));
        List<HomeApps.Item> items = HomeApps.drawerShortcuts();
        for (int i = 0; i < items.size(); i++) shortcuts.addView(chip(items.get(i)), chipParams(i));

        buildSheet();
    }

    private LinearLayout.LayoutParams labelParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        lp.bottomMargin = px(12);
        return lp;
    }

    private LinearLayout.LayoutParams chipParams(int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(176), -1);
        if (i > 0) lp.leftMargin = px(20);
        return lp;
    }

    private TextView text(Typeface face, float size, int color) {
        TextView t = new TextView(getContext());
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(Ui.medium, 24, DIM);
        t.setText(s);
        return t;
    }

    private GradientDrawable cardBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(24));
        return g;
    }

    /** A focusable card: lifts toward you when focused (grows, with a soft shadow below), like Apple TV. */
    private void focusable(View v, float grow) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setElevation(px(2));
        v.setOnFocusChangeListener((view, has) -> lift(view, has, grow));
    }

    private void lift(View view, boolean on, float grow) {
        // translationZ alone puts it on top of its neighbors (moving the child would reorder the row).
        view.animate().scaleX(on ? 1 + grow : 1).scaleY(on ? 1 + grow : 1).translationZ(on ? px(22) : 0)
                .setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** Lets focused cards grow past their row (otherwise each row clips the zoom like a box inside a box). */
    private static void noClip(ViewGroup g) {
        g.setClipChildren(false);
        g.setClipToPadding(false);
    }

    // MARK: - Live TV

    private View liveCard() {
        FrameLayout card = new FrameLayout(getContext());
        tv = new TvView(getContext());
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String inputId) {
                AppLog.w("live: video available");
                liveStatus.setVisibility(INVISIBLE);
            }

            @Override public void onChannelRetuned(String inputId, Uri channelUri) {
                AppLog.w("live: retuned to " + channelUri);
            }

            @Override public void onVideoUnavailable(String inputId, int reason) {
                AppLog.w("live: video unavailable, reason " + reason);
                liveStatus.setVisibility(VISIBLE);
                liveStatus.setText(reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
                        ? Ui.tr("Sem sinal da antena", "No antenna signal")
                        : Ui.tr("Sintonizando…", "Tuning…"));
            }

            @Override public void onConnectionFailed(String inputId) {
                AppLog.w("live: connection failed");
                liveStatus.setVisibility(VISIBLE);
                liveStatus.setText(Ui.tr("A TV não liberou a prévia", "The TV didn't allow the preview"));
                tuned = false;
            }

            @Override public void onDisconnected(String inputId) {
                tuned = false;
            }
        });
        card.addView(tv, new LayoutParams(-1, -1));

        // SurfaceView ignores the outline clip: round the video's corners by painting them in the page color.
        card.addView(new Corners(getContext(), px(24), BG), new LayoutParams(-1, -1));

        liveStatus = text(Ui.medium, 26, 0xFFC7C7CC);
        liveStatus.setGravity(Gravity.CENTER);
        liveStatus.setSingleLine(false);
        card.addView(liveStatus, new LayoutParams(-1, -1));

        liveBadge = text(Ui.semibold, 22, 0xFFFFFFFF);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xB3000000);
        pill.setCornerRadius(px(20));
        liveBadge.setBackground(pill);
        liveBadge.setPadding(px(18), px(9), px(18), px(9));
        liveBadge.setCompoundDrawablePadding(px(10));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(LIVE_RED);
        dot.setSize(px(14), px(14));
        liveBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        LayoutParams badgeLp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        badgeLp.setMargins(px(22), 0, 0, px(22));
        card.addView(liveBadge, badgeLp);

        // The focus ring sits on top of the video.
        View ring = new View(getContext());
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setCornerRadius(px(24));
        ringBg.setColor(Color.TRANSPARENT);
        ring.setBackground(ringBg);
        card.addView(ring, new LayoutParams(-1, -1));

        card.setId(View.generateViewId());
        liveCard = card;
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((v, has) -> ringBg.setStroke(has ? px(6) : 0, RING));
        card.setOnClickListener(v -> openLive());
        return card;
    }

    private ViewOutlineProvider rounded(float radius) {
        final float r = px(radius);
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        };
    }

    /** Finds the configured channel (by number, through the TV's channel list; or the URI in config.properties). */
    private void tuneLive() {
        if (tuned) return;
        String number = Config.LIVE_CHANNEL_NUMBER.trim();
        liveBadge.setText(number.isEmpty() ? Ui.tr("AO VIVO", "LIVE") : Ui.tr("AO VIVO · ", "LIVE · ") + number);
        liveInput = HomeApps.tunerInput(getContext());
        if (liveInput == null) {
            liveStatus.setText(Ui.tr("Esta TV não tem antena", "This TV has no antenna input"));
            return;
        }
        liveStatus.setVisibility(VISIBLE);
        liveStatus.setText(Ui.tr("Sintonizando…", "Tuning…"));
        final Context c = getContext().getApplicationContext();
        final String input = liveInput;
        LOADER.execute(() -> {
            Uri uri = findChannel(c, input, number);
            handler.post(() -> {
                if (!running) return;
                liveChannel = uri;
                if (uri == null) {
                    liveStatus.setText(number.isEmpty() ? Ui.tr("Escolha um canal nos ajustes", "Pick a channel in the settings")
                            : Ui.tr("Canal " + number + " não encontrado", "Channel " + number + " not found"));
                    return;
                }
                tv.setStreamVolume(Config.LIVE_MUTED ? 0f : 1f);
                tv.tune(input, uri);
                tuned = true;
            });
        });
    }

    private static Uri findChannel(Context c, String input, String number) {
        String fixed = Config.LIVE_CHANNEL_URI.trim();
        if (!fixed.isEmpty()) return Uri.parse(fixed);
        // The antenna input's channels first; then the whole list (some TVs file them under another input).
        Uri found = channelIn(c, TvContract.buildChannelsUriForInput(input), number);
        if (found == null) found = channelIn(c, TvContract.Channels.CONTENT_URI, number);
        // TCL keeps its channel list private: ask the antenna input for what it's tuned to (the last channel watched).
        return found != null ? found : TvContract.buildChannelUriForPassthroughInput(input);
    }

    private static Uri channelIn(Context c, Uri list, String number) {
        String[] columns = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER};
        try (Cursor cur = c.getContentResolver().query(list, columns, null, null, null)) {
            if (cur == null) return null;
            AppLog.w("channel list " + list + ": " + cur.getCount() + " channels");
            while (cur.moveToNext()) {
                String n = cur.getString(1);
                if (n != null && (number.equals(n) || number.equals(n.replace('-', '.')))) {
                    return TvContract.buildChannelUri(cur.getLong(0));
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("channel list: " + e.getMessage());
        }
        return null;
    }

    /** Opens the channel full screen in the TV's own live TV app. */
    private void openLive() {
        boolean real = liveChannel != null && TvContract.isChannelUriForTunerInput(liveChannel);
        Intent intent = real ? new Intent(Intent.ACTION_VIEW, liveChannel) : HomeApps.liveTv(getContext());
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent fallback = HomeApps.liveTv(getContext());
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(fallback);
            }
        }
    }

    /** Four rounded corners in the page color (rounds the live video, which can't be clipped). */
    static final class Corners extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float r;

        Corners(Context c, float radius, int color) {
            super(c);
            r = radius;
            paint.setColor(color);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawPath(path, paint);
        }
    }

    // MARK: - Weather and prices

    private View weatherCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setBackground(cardBg(CARD));
        card.setElevation(px(2));
        card.setPadding(px(36), px(22), px(36), px(22));

        LinearLayout now = new LinearLayout(getContext());
        now.setOrientation(LinearLayout.VERTICAL);
        weatherCity = text(Ui.medium, 24, DIM);
        LinearLayout tempRow = new LinearLayout(getContext());
        tempRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(getContext());
        tempRow.addView(weatherIcon, new LinearLayout.LayoutParams(px(54), px(54)));
        weatherTemp = text(Ui.thin, 76, INK);
        weatherTemp.setPadding(px(14), 0, 0, 0);
        tempRow.addView(weatherTemp);
        weatherCondition = text(Ui.medium, 26, INK);
        weatherRange = text(Ui.regular, 22, FAINT);
        now.addView(weatherCity);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.topMargin = px(6);
        now.addView(tempRow, tLp);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.topMargin = px(6);
        now.addView(weatherCondition, cLp);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-2, -2);
        rLp.topMargin = px(8);
        now.addView(weatherRange, rLp);
        card.addView(now, new LinearLayout.LayoutParams(px(330), -1));

        View divider = new View(getContext());
        divider.setBackgroundColor(0x1F000000);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(px(2), -1);
        dLp.setMargins(px(10), px(4), px(30), px(4));
        card.addView(divider, dLp);

        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout hours = new LinearLayout(getContext());
        for (int i = 0; i < 6; i++) {
            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView h = text(Ui.medium, 22, FAINT);
            ImageView icon = new ImageView(getContext());
            TextView t = text(Ui.medium, 26, INK);
            col.addView(h);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(px(40), px(40));
            iLp.setMargins(0, px(12), 0, px(12));
            col.addView(icon, iLp);
            col.addView(t);
            icon.setTag("icon");
            hours.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            hourViews.add(new TextView[] {h, t});
            h.setTag(icon);
        }
        right.addView(hours, new LinearLayout.LayoutParams(-1, 0, 1));
        weatherSun = text(Ui.regular, 22, DIM);
        right.addView(weatherSun);
        card.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        return card;
    }

    private View quotesCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setBackground(cardBg(CARD));
        card.setElevation(px(2));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(px(36), px(14), px(36), px(14));
        for (int i = 0; i < HomeData.coinCount(); i++) {
            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(Ui.medium, 22, DIM);
            name.setText(HomeData.coinName(i));
            TextView price = text(Ui.semibold, 28, INK);
            price.setFontFeatureSettings("'tnum'");
            TextView change = text(Ui.medium, 20, FAINT);
            col.addView(name);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
            pLp.topMargin = px(8);
            col.addView(price, pLp);
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(-2, -2);
            chLp.topMargin = px(6);
            col.addView(change, chLp);
            card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            quoteViews.add(new TextView[] {price, change});
        }
        return card;
    }

    @Override public void onData() {
        HomeData.Weather w = data.weather;
        String city = Config.CITY.trim();
        weatherCity.setText(city.isEmpty() ? Ui.tr("Tempo", "Weather") : city);
        weatherTemp.setText(HomeData.degrees(w.temp));
        weatherCondition.setText(HomeData.condition(w.code, w.isDay));
        weatherRange.setText(Double.isNaN(w.max) ? "" : Ui.tr("Máx. ", "H ") + HomeData.degrees(w.max)
                + Ui.tr(" · Mín. ", " · L ") + HomeData.degrees(w.min));
        symbol(weatherIcon, w.code < 0 ? null : Symbols.weather(w.code, w.isDay), INK);
        for (int i = 0; i < hourViews.size(); i++) {
            TextView[] v = hourViews.get(i);
            boolean has = i < w.hours.size();
            v[0].setText(has ? HomeData.hour(w.hours.get(i)) : "");
            v[1].setText(has ? HomeData.degrees(w.hourTemp.get(i)) : "");
            symbol((ImageView) v[0].getTag(), has ? Symbols.weather(w.hourCode.get(i), w.hourDay.get(i)) : null, DIM);
        }
        weatherSun.setText(w.sunrise == 0 ? "" : Ui.tr("Nascer do sol ", "Sunrise ") + Ui.time(w.sunrise)
                + "     " + Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(w.sunset));
        for (int i = 0; i < quoteViews.size(); i++) {
            TextView[] v = quoteViews.get(i);
            v[0].setText(HomeData.brl(data.prices[i]));
            double ch = data.changes[i];
            v[1].setText(HomeData.change(ch));
            v[1].setTextColor(Double.isNaN(ch) ? FAINT : ch >= 0 ? UP : DOWN);
        }
    }

    private void symbol(ImageView v, String name, int color) {
        Bitmap b = name == null ? null : Symbols.get(getContext(), name);
        v.setImageBitmap(b);
        v.setColorFilter(color);
    }

    private void updateClock() {
        long now = Ui.now();
        clock.setText(Ui.clock(now));
        date.setText(Ui.date(now));
    }

    // MARK: - Apps, inputs and shortcuts

    private void loadShelf() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<HomeApps.Item> items = HomeApps.shelf(c, false);
            handler.post(() -> setShelf(items));
        });
    }

    private void setShelf(List<HomeApps.Item> items) {
        boolean hadFocus = shelfRow.hasFocus();
        int focused = -1;
        for (int i = 0; i < shelfRow.getChildCount(); i++) if (shelfRow.getChildAt(i).hasFocus()) focused = i;
        shelfRow.removeAllViews();
        firstApp = null;
        for (int i = 0; i < items.size(); i++) {
            HomeApps.Item it = items.get(i);
            ImageView tile = new ImageView(getContext());
            tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.setImageBitmap(it.tile);
            GradientDrawable bg = cardBg(0xFF2C2C2E);
            tile.setBackground(bg);
            tile.setOutlineProvider(rounded(18));
            tile.setClipToOutline(true);
            tile.setContentDescription(it.label);
            focusable(tile, 0.14f);
            tile.setOnClickListener(v -> open(it, v));
            tile.setId(View.generateViewId());
            tile.setNextFocusUpId(liveCard.getId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(198), px(111));
            if (i > 0) lp.leftMargin = px(22);
            lp.gravity = Gravity.CENTER_VERTICAL;
            shelfRow.addView(tile, lp);
            if (firstApp == null) firstApp = tile;
        }
        if (firstApp != null) liveCard.setNextFocusDownId(firstApp.getId());
        if (hadFocus && focused >= 0 && focused < shelfRow.getChildCount()) shelfRow.getChildAt(focused).requestFocus();
        else if (!startFocused && firstApp != null) firstApp.requestFocus();
        startFocused = true;
    }

    private void loadInputs() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<HomeApps.Item> items = HomeApps.allInputs(c);
            handler.post(() -> {
                View focused = inputsRow.findFocus();
                int index = focused == null ? -1 : inputsRow.indexOfChild(focused);
                inputsRow.removeAllViews();
                for (int i = 0; i < items.size(); i++) inputsRow.addView(chip(items.get(i)), chipParams(i));
                if (index >= 0 && index < inputsRow.getChildCount()) inputsRow.getChildAt(index).requestFocus();
            });
        });
    }

    /** Square button with a symbol and a name (inputs with nothing plugged in are dimmed). */
    private View chip(HomeApps.Item it) {
        LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        GradientDrawable bg = cardBg(CARD);
        bg.setCornerRadius(px(20));
        chip.setBackground(bg);
        chip.setPadding(px(10), px(10), px(10), px(10));
        ImageView icon = new ImageView(getContext());
        boolean codeTv = it.kind == HomeApps.CHANNELS;
        if (codeTv) icon.setImageResource(R.drawable.codetv_robot);   // its own colors, not a tinted symbol
        else symbol(icon, it.symbol, INK);
        chip.addView(icon, new LinearLayout.LayoutParams(px(codeTv ? 46 : 36), px(codeTv ? 46 : 36)));
        TextView name = text(Ui.medium, 22, INK);
        name.setText(it.label);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-2, -2);
        nLp.topMargin = px(10);
        chip.addView(name, nLp);
        chip.setAlpha(it.connected ? 1f : 0.55f);
        chip.setOutlineProvider(rounded(20));
        focusable(chip, 0.1f);
        chip.setOnClickListener(v -> open(it, v));
        return chip;
    }

    private void open(HomeApps.Item it, View from) {
        if (it.kind == HomeApps.CHANNELS) {
            activity.startActivity(new Intent(activity, ChannelsActivity.class));
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.LIVE_TV) {
            openLive();
            return;
        }
        HomeApps.open(activity, it, from, 0, 0, from.getWidth(), from.getHeight());
    }

    // MARK: - "All Apps"

    /** Same card size as the home shelf, so the grid reads as the shelf continued. */
    private static final int GRID_COLUMNS = 8;
    private static final float GRID_W = 198, GRID_H = 111, GRID_GAP = 22;

    private TextView sheetCount;

    private void buildSheet() {
        sheet = new FrameLayout(getContext());
        sheet.setVisibility(GONE);
        sheet.setClickable(true);
        noClip(sheet);
        LinearLayout box = new LinearLayout(getContext());
        noClip(box);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(80), px(48), px(80), 0);

        // Header like the home screen's clock line: light title, dim detail beside it.
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.BOTTOM);
        TextView title = text(Ui.light, 52, INK);
        title.setText(Ui.tr("Todos os Apps", "All Apps"));
        sheetCount = text(Ui.regular, 26, DIM);
        sheetCount.setPadding(px(24), 0, 0, px(8));
        header.addView(title);
        header.addView(sheetCount);
        box.addView(header, new LinearLayout.LayoutParams(-1, px(96)));

        grid = new GridView(getContext());
        grid.setNumColumns(GRID_COLUMNS);
        grid.setColumnWidth(px(GRID_W));
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setHorizontalSpacing(px(GRID_GAP));
        grid.setVerticalSpacing(px(30));
        grid.setPadding(0, px(36), 0, px(60));
        noClip(grid);
        // No scrollbar, no edge glow, no fade: just the cards.
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalFadingEdgeEnabled(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        grid.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            HomeApps.Item it = (HomeApps.Item) parent.getItemAtPosition(position);
            open(it, view);
        });
        // Every visible cell follows the selection (the first one can be selected before it exists).
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View cell = parent.getChildAt(i);
                    boolean on = cell == view;
                    if (on != cell.getTranslationZ() > 0) highlight(cell, on);
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                for (int i = 0; i < parent.getChildCount(); i++) highlight(parent.getChildAt(i), false);
            }
        });
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, -1);
        gLp.topMargin = px(20);
        box.addView(grid, gLp);
        sheet.addView(box, new LayoutParams(-1, -1));
        addView(sheet, new LayoutParams(-1, -1));
    }

    /** The same lift as the shelf: the card grows with a soft shadow; the cell's Z keeps it above its neighbors. */
    private void highlight(View cell, boolean on) {
        if (cell == null) return;
        View image = cell.findViewWithTag("image");
        TextView name = (TextView) cell.findViewWithTag("name");
        if (image == null) return;
        cell.setTranslationZ(on ? px(22) : 0);
        image.animate().scaleX(on ? 1.14f : 1).scaleY(on ? 1.14f : 1).translationZ(on ? px(22) : 0)
                .setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        if (name != null) {
            name.setTextColor(on ? INK : DIM);
            name.animate().translationY(on ? px(9) : 0).setDuration(220).start();
        }
    }

    /** A recycled cell shown at once in its state (no animation from the cell it used to be). */
    private void settle(View cell, boolean on) {
        View image = cell.findViewWithTag("image");
        TextView name = (TextView) cell.findViewWithTag("name");
        image.animate().cancel();
        name.animate().cancel();
        image.setScaleX(on ? 1.14f : 1);
        image.setScaleY(on ? 1.14f : 1);
        image.setTranslationZ(on ? px(22) : 0);
        cell.setTranslationZ(on ? px(22) : 0);
        name.setTranslationY(on ? px(9) : 0);
        name.setTextColor(on ? INK : DIM);
    }

    private void openSheet() {
        sheet.setBackground(blurredPage());
        sheet.setVisibility(VISIBLE);
        sheet.setAlpha(0);
        sheet.animate().alpha(1).setDuration(220).withEndAction(null).start();
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<HomeApps.Item> items = HomeApps.others(c);
            handler.post(() -> {
                sheetCount.setText(items.size() + " apps");
                grid.setAdapter(new AppsAdapter(items));
                grid.requestFocus();
                grid.setSelection(0);
            });
        });
    }

    private void closeSheet() {
        if (sheet == null || sheet.getVisibility() != VISIBLE) return;
        sheet.animate().alpha(0).setDuration(160).withEndAction(() -> {
            sheet.setVisibility(GONE);
            sheet.setBackground(null);
            grid.setAdapter(null);
        }).start();
        if (firstApp != null) firstApp.requestFocus();
    }

    /**
     * The home screen behind the sheet, blurred (Android 9 has no blur effect for views): drawn into a 1/12-size
     * bitmap, box-blurred, scaled back up with filtering, under a light veil so the cards stay readable.
     */
    private Drawable blurredPage() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        int bw = Math.max(1, w / 12), bh = Math.max(1, h / 12);
        Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        c.drawColor(BG);
        c.scale(bw / (float) w, bh / (float) h);
        page.draw(c);
        // The live video isn't drawn by the views (it's a separate layer): stand in a dark card for it.
        Rect r = new Rect();
        liveCard.getDrawingRect(r);
        offsetDescendantRectToMyCoords(liveCard, r);
        Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        dark.setColor(0xFF6E6E73);
        c.drawRoundRect(new RectF(r), px(24), px(24), dark);
        int[] pixels = new int[bw * bh];
        small.getPixels(pixels, 0, bw, 0, 0, bw, bh);
        for (int pass = 0; pass < 4; pass++) boxBlur(pixels, bw, bh, 3);
        small.setPixels(pixels, 0, bw, 0, 0, bw, bh);
        BitmapDrawable blur = new BitmapDrawable(getResources(), small);
        blur.setFilterBitmap(true);
        return new LayerDrawable(new Drawable[] {blur, new ColorDrawable(0xC2EBEBF0)});
    }

    /** One horizontal and one vertical box-blur pass (radius r) over ARGB pixels. */
    private static void boxBlur(int[] p, int w, int h, int r) {
        int[] out = new int[p.length];
        for (int pass = 0; pass < 2; pass++) {
            boolean horizontal = pass == 0;
            int lines = horizontal ? h : w, len = horizontal ? w : h;
            for (int line = 0; line < lines; line++) {
                for (int i = 0; i < len; i++) {
                    int a = 0, red = 0, g = 0, b = 0, n = 0;
                    for (int k = -r; k <= r; k++) {
                        int j = Math.max(0, Math.min(len - 1, i + k));
                        int c = p[horizontal ? line * w + j : j * w + line];
                        a += c >>> 24;
                        red += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                    out[horizontal ? line * w + i : i * w + line] = (a / n) << 24 | (red / n) << 16 | (g / n) << 8 | b / n;
                }
            }
            System.arraycopy(out, 0, p, 0, p.length);
        }
    }

    private final class AppsAdapter extends BaseAdapter {
        private final List<HomeApps.Item> items;

        AppsAdapter(List<HomeApps.Item> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(getContext());
                noClip(cell);
                cell.setOrientation(LinearLayout.VERTICAL);
                ImageView image = new ImageView(getContext());
                image.setTag("image");
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackground(cardBg(0xFF2C2C2E));
                image.setOutlineProvider(rounded(18));
                image.setClipToOutline(true);
                image.setElevation(px(2));
                cell.addView(image, new LinearLayout.LayoutParams(px(GRID_W), px(GRID_H)));
                TextView name = text(Ui.regular, 20, DIM);
                name.setTag("name");
                LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(px(GRID_W), -2);
                nLp.topMargin = px(12);
                cell.addView(name, nLp);
            }
            HomeApps.Item it = items.get(position);
            ((ImageView) cell.findViewWithTag("image")).setImageBitmap(it.tile);
            ((TextView) cell.findViewWithTag("name")).setText(it.label);
            settle(cell, grid.hasFocus() && position == grid.getSelectedItemPosition());
            return cell;
        }
    }
}

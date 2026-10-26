package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The open channels as a guide, in the home screen's look: regions on the left, the region's channels in the middle
 * (logo, name and category, alphabetical), and on the right a live preview of the selected channel. OK opens it full screen
 * (PlayerActivity); Back returns to the home screen.
 */
public class ChannelsActivity extends Activity {
    private static final int BG = 0xFFEBEBF0, CARD = 0xFFFFFFFF, INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, FAINT = 0xFF8E8E93;
    private static final long PREVIEW_DELAY_MS = 650, CLOCK_WAIT_MS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float k;
    private Map<String, List<Channels.Channel>> sections;
    private final List<String> groups = new ArrayList<>();
    private final List<View> sectionRows = new ArrayList<>();
    private String group;
    private List<Channels.Channel> list = new ArrayList<>();
    private ListView listView;
    private VideoView video;
    private View curtain;
    private final Runnable reveal = () -> curtain.animate().alpha(0).setDuration(250).start();
    private TextView previewStatus, previewName, previewDetail;
    private Channels.Channel previewing;
    private long previewAt;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Watchdog.install(this);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        sections = Channels.sections(this);
        groups.addAll(sections.keySet());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(px(80), px(48), px(80), px(40));
        noClip(page);

        // Header, like the home screen's clock line.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.BOTTOM);
        TextView title = text(Ui.light, 52, INK);
        title.setText(Ui.tr("Canais abertos", "Free channels"));
        int total = 0;
        for (List<Channels.Channel> l : sections.values()) total += l.size();
        TextView count = text(Ui.regular, 26, DIM);
        count.setText(total + Ui.tr(" canais gratuitos", " free channels"));
        count.setPadding(px(24), 0, 0, px(8));
        header.addView(title);
        header.addView(count);
        page.addView(header, new LinearLayout.LayoutParams(-1, px(96)));

        LinearLayout body = new LinearLayout(this);
        noClip(body);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyLp.topMargin = px(28);
        page.addView(body, bodyLp);

        // Sections
        LinearLayout sectionCol = new LinearLayout(this);
        sectionCol.setOrientation(LinearLayout.VERTICAL);
        noClip(sectionCol);
        for (String g : groups) {
            View row = sectionRow(g, sections.get(g).size());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(76));
            lp.bottomMargin = px(10);
            sectionCol.addView(row, lp);
            sectionRows.add(row);
        }
        body.addView(sectionCol, new LinearLayout.LayoutParams(px(300), -1));

        // Channel list
        listView = new ListView(this);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(px(8));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setVerticalFadingEdgeEnabled(false);
        listView.setClipToPadding(false);
        listView.setClipChildren(false);
        listView.setPadding(px(12), px(4), px(12), px(40));
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View row = parent.getChildAt(i);
                    highlight(row, row == view);
                }
                schedulePreview(list.get(position));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Channels.Channel ch = list.get(position);
            PlayerActivity.open(this, ch.group, ch.index);
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(px(744), -1);
        listLp.leftMargin = px(28);
        body.addView(listView, listLp);

        // Preview
        LinearLayout previewCol = new LinearLayout(this);
        previewCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, -1, 1);
        pLp.leftMargin = px(40);
        body.addView(previewCol, pLp);
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        video.setFocusable(false);
        preview.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // Covers the previous channel's frozen last frame until the new one shows.
        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        preview.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
        preview.addView(new HomeView.Corners(this, px(24), BG), new FrameLayout.LayoutParams(-1, -1));
        previewStatus = text(Ui.medium, 26, 0xFFC7C7CC);
        previewStatus.setGravity(Gravity.CENTER);
        previewStatus.setSingleLine(false);
        preview.addView(previewStatus, new FrameLayout.LayoutParams(-1, -1));
        previewCol.addView(preview, new LinearLayout.LayoutParams(-1, px(340)));
        previewName = text(Ui.medium, 36, INK);
        previewName.setSingleLine(false);
        previewName.setMaxLines(2);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
        nLp.topMargin = px(28);
        previewCol.addView(previewName, nLp);
        previewDetail = text(Ui.regular, 24, DIM);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
        dLp.topMargin = px(10);
        previewCol.addView(previewDetail, dLp);
        TextView hint = text(Ui.regular, 22, FAINT);
        hint.setText(Ui.tr("OK para assistir em tela cheia", "OK to watch full screen"));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.topMargin = px(22);
        previewCol.addView(hint, hLp);

        video.setOnPreparedListener(mp -> {
            if (Config.LIVE_MUTED) mp.setVolume(0, 0);
            previewStatus.setText("");
            handler.postDelayed(reveal, 1500);   // in case the player never reports its first frame
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) reveal.run();
            return false;
        });
        video.setOnErrorListener((mp, what, extra) -> {
            previewStatus.setText(Ui.tr("Este canal não abriu agora", "This channel didn't open"));
            return true;
        });

        setContentView(page);
        showSection(groups.isEmpty() ? null : groups.get(0));
        // Logos download as rows need them: redraw the visible rows when they arrive (at most 4 times a second).
        Channels.logoListener = () -> {
            handler.removeCallbacks(redrawRows);
            handler.postDelayed(redrawRows, 250);
        };
        listView.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        if (previewing != null) schedulePreview(previewing);
    }

    private final Runnable redrawRows = () -> {
        if (listView.getAdapter() != null) ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
    };

    @Override protected void onDestroy() {
        Channels.logoListener = null;
        super.onDestroy();
    }

    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        video.stopPlayback();
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // Channel +/− walk the list too, like on the TV's own guide.
        if (e.getAction() == KeyEvent.ACTION_DOWN && listView.hasFocus()) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP) return listView.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, e);
            if (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_DOWN) return listView.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, e);
        }
        return super.dispatchKeyEvent(e);
    }

    // MARK: - Sections

    private View sectionRow(String g, int size) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(26), 0, px(24), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(bg);
        row.setOutlineProvider(rounded(18));
        TextView name = text(Ui.medium, 28, INK);
        name.setText(Channels.title(g));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text(Ui.regular, 22, FAINT);
        count.setText(String.valueOf(size));
        row.addView(count);
        row.setTag(g);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnFocusChangeListener((v, has) -> {
            if (has) showSection(g);
            lift(v, has);
        });
        row.setOnClickListener(v -> listView.requestFocus());
        return row;
    }

    private void showSection(String g) {
        if (g == null || g.equals(group)) return;
        group = g;
        list = sections.get(g);
        for (View row : sectionRows) {
            boolean current = g.equals(row.getTag());
            ((GradientDrawable) row.getBackground()).setColor(current ? CARD : Color.TRANSPARENT);
        }
        listView.setAdapter(new ListAdapter());
        listView.setSelection(0);
        if (!list.isEmpty()) schedulePreview(list.get(0));
    }

    // MARK: - Preview

    private void schedulePreview(Channels.Channel ch) {
        previewing = ch;
        previewName.setText(ch.name);
        previewDetail.setText(Channels.detail(ch));
        previewStatus.setText(Ui.tr("Carregando…", "Loading…"));
        handler.removeCallbacks(reveal);
        curtain.animate().cancel();
        curtain.setAlpha(1);
        video.stopPlayback();
        handler.removeCallbacks(startPreview);
        previewAt = System.currentTimeMillis();
        handler.postDelayed(startPreview, PREVIEW_DELAY_MS);
    }

    /** Starts the preview once the TV's clock is right (the home screen's live TV may have left a wrong date). */
    private final Runnable startPreview = new Runnable() {
        @Override public void run() {
            if (!Ui.clockOk(System.currentTimeMillis()) && System.currentTimeMillis() - previewAt < CLOCK_WAIT_MS) {
                handler.postDelayed(this, 300);
                return;
            }
            Channels.Channel ch = previewing;
            if (ch == null) return;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", ch.userAgent != null ? ch.userAgent : "Mozilla/5.0 (Linux; Android 9) FreeMyTV");
            if (ch.referrer != null) headers.put("Referer", ch.referrer);
            video.setVideoURI(Uri.parse(ch.url), headers);
            video.start();
        }
    };

    // MARK: - List

    private final class ListAdapter extends BaseAdapter {
        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int position) { return list.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) row = rowView();
            Channels.Channel ch = list.get(position);
            ((TextView) row.findViewWithTag("name")).setText(ch.name);
            TextView category = (TextView) row.findViewWithTag("category");
            boolean none = ch.category == null || ch.category.isEmpty();
            category.setText(none ? "" : ch.category);
            category.setVisibility(none ? View.GONE : View.VISIBLE);
            Bitmap b = Channels.logo(ChannelsActivity.this, ch);
            ImageView logo = (ImageView) row.findViewWithTag("logo");
            logo.setImageBitmap(b);
            ((GradientDrawable) logo.getBackground()).setColor(b != null && Channels.lightLogo(ch) ? 0xFF2C2C2E : 0xFFF2F2F7);
            highlight(row, listView.hasFocus() && position == listView.getSelectedItemPosition());
            return row;
        }
    }

    /** A channel row: its logo on a small card, then the name with the category below. */
    private LinearLayout rowView() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14), 0, px(28), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(bg);
        row.setOutlineProvider(rounded(18));
        row.setMinimumHeight(px(84));
        row.setLayoutParams(new ListView.LayoutParams(-1, px(84)));
        ImageView logo = new ImageView(this);
        logo.setTag("logo");
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable lbg = new GradientDrawable();
        lbg.setCornerRadius(px(12));
        logo.setBackground(lbg);
        logo.setPadding(px(10), px(8), px(10), px(8));
        row.addView(logo, new LinearLayout.LayoutParams(px(108), px(60)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(Ui.regular, 26, INK);
        name.setTag("name");
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView category = text(Ui.regular, 20, FAINT);
        category.setTag("category");
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.topMargin = px(8);
        texts.addView(category, cLp);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1);
        tLp.leftMargin = px(22);
        row.addView(texts, tLp);
        return row;
    }

    /**
     * Selected row: a flat white card and a bolder name. No shadow or depth animation: inside a scrolling list this
     * TV's GPU leaves a dark trail where the shadow fades out.
     */
    private void highlight(View row, boolean on) {
        ((GradientDrawable) row.getBackground()).setColor(on ? CARD : Color.TRANSPARENT);
        TextView name = (TextView) row.findViewWithTag("name");
        if (name != null) name.setTypeface(on ? Ui.medium : Ui.regular);
    }

    private void lift(View v, boolean on) {
        v.animate().translationZ(on ? px(14) : 0).scaleX(on ? 1.03f : 1).scaleY(on ? 1.03f : 1)
                .setDuration(200).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    // MARK: - Helpers

    private int px(float v) {
        return Math.round(v * k);
    }

    private static void noClip(ViewGroup g) {
        g.setClipChildren(false);
        g.setClipToPadding(false);
    }

    private ViewOutlineProvider rounded(float radius) {
        final float r = px(radius);
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        };
    }

    private TextView text(Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}

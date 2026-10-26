package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free, open channels (assets/channels/channels.m3u): public lists from iptv-org, tested and filtered to official
 * free streams (no pay-TV restreams, no Pluto feeds). One section per region (group-title), alphabetical inside, each
 * channel with its category (tvg-category, already in Portuguese) and its logo bundled (the TV's clock can't be
 * trusted for downloads while the antenna plays).
 */
final class Channels {
    private Channels() {}

    static final class Channel {
        String name, group, category, url, userAgent, referrer, logo;
        int index;          // position within its section
    }

    private static Map<String, List<Channel>> sections;
    /** Regions in this order (Brasil, Portugal, then Latin America, sports last). */
    private static final String[] ORDER = {"Brasil", "Portugal", "Argentina", "Chile", "Colômbia", "México", "Peru",
            "Esportes"};
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();
    private static final java.util.Set<String> LIGHT = new java.util.HashSet<>();
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    /** Sections in order (Brasil, Portugal, …), each with its channels. Read once per process. */
    static synchronized Map<String, List<Channel>> sections(Context c) {
        if (sections != null) return sections;
        Map<String, List<Channel>> out = new LinkedHashMap<>();
        try (InputStream in = c.getAssets().open("channels/channels.m3u")) {
            parse(in, out);
        } catch (IOException e) {
            AppLog.w("channels: " + e.getMessage());
        }
        Map<String, List<Channel>> ordered = new LinkedHashMap<>();
        for (String k : ORDER) if (out.containsKey(k)) ordered.put(k, Collections.unmodifiableList(out.get(k)));
        for (String k : out.keySet()) if (!ordered.containsKey(k)) ordered.put(k, Collections.unmodifiableList(out.get(k)));
        sections = ordered;
        return sections;
    }

    /** Reads an M3U playlist into sections (group-title). */
    private static void parse(InputStream in, Map<String, List<Channel>> out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Channel ch = null;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                ch = new Channel();
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    if (m.group(1).equals("group-title")) ch.group = m.group(2);
                    else if (m.group(1).equals("tvg-logo")) ch.logo = m.group(2);
                    else if (m.group(1).equals("tvg-category")) ch.category = m.group(2);
                }
                ch.name = line.substring(line.lastIndexOf(',') + 1).trim();
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=") && ch != null) {
                ch.userAgent = line.substring(27);
            } else if (line.startsWith("#EXTVLCOPT:http-referrer=") && ch != null) {
                ch.referrer = line.substring(25);
            } else if (!line.isEmpty() && !line.startsWith("#") && ch != null) {
                ch.url = line;
                if (ch.group == null || ch.group.isEmpty()) ch.group = "Canais";
                List<Channel> list = out.get(ch.group);
                if (list == null) out.put(ch.group, list = new ArrayList<>());
                ch.index = list.size();
                list.add(ch);
                ch = null;
            }
        }
    }

    static List<Channel> section(Context c, String group) {
        List<Channel> list = sections(c).get(group);
        return list != null ? list : Collections.<Channel>emptyList();
    }

    /**
     * The channel's logo, or null for now. tvg-logo is the logo's URL: the first time it's needed it is downloaded in
     * the background, shrunk to fit 240 × 120 and kept on the TV (files/logos); `logoListener` hears when one arrives.
     * The logos are the TV stations' marks, so they're fetched by each TV rather than shipped with the app.
     */
    static Bitmap logo(Context c, Channel ch) {
        if (ch.logo == null || ch.logo.isEmpty()) return null;
        synchronized (LOGOS) {
            if (LOGOS.containsKey(ch.logo)) return LOGOS.get(ch.logo);
        }
        File saved = logoFile(c, ch.logo);
        if (!saved.exists()) {
            fetchLogo(c.getApplicationContext(), ch.logo, saved);
            return null;
        }
        Bitmap b = BitmapFactory.decodeFile(saved.getPath());
        remember(ch.logo, b);
        return b;
    }

    /** Runs on the main thread each time a downloaded logo is ready (the guide redraws its rows). */
    static volatile Runnable logoListener;

    private static final java.util.concurrent.ExecutorService LOGO_LOADER = java.util.concurrent.Executors.newFixedThreadPool(3);
    private static final java.util.Set<String> FETCHING = new java.util.HashSet<>();

    private static File logoFile(Context c, String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", d[i]));
            return new File(new File(c.getFilesDir(), "logos"), hex + ".png");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void remember(String key, Bitmap b) {
        boolean light = b != null && light(b);
        synchronized (LOGOS) {
            LOGOS.put(key, b);
            if (light) LIGHT.add(key);
        }
    }

    private static void fetchLogo(Context app, String url, File dest) {
        synchronized (FETCHING) {
            if (!FETCHING.add(url)) return;
        }
        LOGO_LOADER.execute(() -> {
            try {
                // HTTPS: wait for the TV's clock (the antenna on the home screen sets a wrong date).
                long until = System.currentTimeMillis() + 10_000;
                while (!Ui.clockOk(System.currentTimeMillis()) && System.currentTimeMillis() < until) {
                    android.os.SystemClock.sleep(300);
                }
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                h.setRequestProperty("User-Agent", "FreeMyTV-logos/1.0 (TV launcher)");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                try (InputStream in = h.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && body.size() < 4_000_000) body.write(buf, 0, n);
                } finally {
                    h.disconnect();
                }
                Bitmap b = shrink(body.toByteArray());
                if (b == null) throw new IOException("not an image");
                File dir = dest.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("no logos folder");
                File tmp = new File(dest.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                if (!tmp.renameTo(dest)) throw new IOException("could not save");
                remember(url, b);
                Runnable l = logoListener;
                if (l != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(l);
            } catch (IOException | RuntimeException e) {
                remember(url, null);   // no logo this session: the row shows the name alone
            }
        });
    }

    /** Decodes at a reduced size (big logos are 1000+ px) and fits it in 240 × 120. */
    private static Bitmap shrink(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 240 && o.outHeight / (o.inSampleSize * 2) >= 120) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) return null;
        float k = Math.min(240f / b.getWidth(), 120f / b.getHeight());
        if (k >= 1) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    /** A white (or nearly white) logo: it goes on a dark card, or it would vanish on a white one. */
    static boolean lightLogo(Channel ch) {
        synchronized (LOGOS) {
            return ch.logo != null && LIGHT.contains(ch.logo);
        }
    }

    /** Average brightness of the logo's visible pixels, sampled. */
    private static boolean light(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 24);
        double sum = 0;
        int n = 0, total = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p = b.getPixel(x, y);
                total++;
                if ((p >>> 24) < 128) continue;
                sum += (0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255)) / 255;
                n++;
            }
        }
        // Only logos with a transparent background: one that brings its own white box stays on white.
        return n > 0 && n < total * 0.85 && sum / n > 0.86;
    }

    /** "Brasil · Notícias" (the region, and the category when the list has one). */
    static String detail(Channel ch) {
        return title(ch.group) + (ch.category == null || ch.category.isEmpty() ? "" : " · " + ch.category);
    }

    /** Section titles as shown on screen. */
    static String title(String group) {
        switch (group) {
            case "Esportes": return Ui.tr("Esportes", "Sports");
            case "Colômbia": return Ui.tr("Colômbia", "Colombia");
            case "México": return Ui.tr("México", "Mexico");
            default: return group;
        }
    }
}

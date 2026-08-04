package io.github.nasmanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide, bounded icon cache. Targets are weakly referenced to avoid retaining activities. */
final class AppIconLoader {
    private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24) {
        @Override protected int sizeOf(String key, Bitmap value) { return 1; }
    };
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private AppIconLoader() { }

    static void load(String url, ImageView target) {
        if (url == null || url.trim().isEmpty()) return;
        String key = url.trim();
        android.net.Uri uri = android.net.Uri.parse(key);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return;
        target.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            target.setAlpha(1f);
            return;
        }
        WeakReference<ImageView> reference = new WeakReference<>(target);
        EXECUTOR.execute(() -> {
            Bitmap bitmap = download(key);
            ImageView view = reference.get();
            if (bitmap == null || view == null) return;
            CACHE.put(key, bitmap);
            view.post(() -> {
                ImageView current = reference.get();
                if (current == null || !key.equals(current.getTag())) return;
                current.setImageBitmap(bitmap);
                current.animate().alpha(1f).setDuration(180).start();
            });
        });
    }

    private static Bitmap download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");
            int length = connection.getContentLength();
            if (length > MAX_ICON_BYTES) return null;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(1024, Math.min(length, 64 * 1024)));
                byte[] chunk = new byte[8192];
                int total = 0;
                int count;
                while ((count = stream.read(chunk)) >= 0) {
                    total += count;
                    if (total > MAX_ICON_BYTES) return null;
                    bytes.write(chunk, 0, count);
                }
                byte[] encoded = bytes.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
                int sample = 1;
                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
                return bitmap == null ? null : Bitmap.createScaledBitmap(bitmap, 144, 144, true);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

}

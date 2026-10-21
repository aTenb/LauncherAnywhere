package la.launcheranywhere;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The order in which tiles first appeared on the home screen (appearance.txt). Fresh installs and apps
 * checked from the library join the end, so the default sort is simply "last appeared sorts last".
 * The owner's manual order (Order) overrides this while it is present.
 *
 * Format, UTF-8 text: one package name per line, in appearance order. Package names (not components)
 * so a library check-in matches the tile however many entry points the app has.
 */
final class Appearance {
    private static final String TAG = "TvHome.Appearance";
    static final String FILE_NAME = "appearance.txt";

    private Appearance() { }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static List<String> load(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return new ArrayList<>();
        try {
            String text = new String(new AtomicFile(f).readFully(), StandardCharsets.UTF_8);
            List<String> keys = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty()) continue;
                if (!keys.contains(line)) keys.add(line);
            }
            return keys;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not read " + FILE_NAME + ": " + e);
            return new ArrayList<>();
        }
    }

    static boolean save(Context ctx, List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('\n');
        AtomicFile af = new AtomicFile(file(ctx));
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "could not write " + FILE_NAME + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }

    /** Brings a tile to the end of the appearance order (a library check-in, or a fresh install). */
    static void moveToEnd(Context ctx, String key) {
        List<String> keys = load(ctx);
        keys.remove(key);
        keys.add(key);
        save(ctx, keys);
    }

    /** Drops a tile from the appearance order (a library uncheck; it no longer appears). */
    static void remove(Context ctx, String key) {
        List<String> keys = load(ctx);
        if (keys.remove(key)) save(ctx, keys);
    }
}

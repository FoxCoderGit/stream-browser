package dev.stream.browser;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.gecko.EventDispatcher;
import org.mozilla.gecko.util.GeckoBundle;
import org.mozilla.geckoview.GeckoResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class BrowserApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (!getPackageName().equals(Application.getProcessName())) return;
        java.io.File[] stale = getCacheDir().listFiles((dir, name) -> name.startsWith("cookies-") && name.endsWith(".txt"));
        if (stale != null) for (java.io.File file : stale) file.delete();
    }
    GeckoRuntime runtime;
    GeckoSession session;
    String page = "https://example.org";
    boolean hookReady;
    private final LinkedHashMap<String, Candidate> media = new LinkedHashMap<>();
    Playback playback;
    Runnable onMedia = () -> {};

    void initBrowser() {
        if (runtime != null) return;
        EventDispatcher.getInstance().registerUiThreadListener((event, message, callback) -> {
            if ("Stream:Selection".equals(event)) {
                if (!page.equals(message.getString("page", ""))) return;
                playback = new Playback(message.getString("frame", ""), message.getString("revision", ""),
                    message.getString("source", ""), message.getString("title", ""),
                    message.getInt("width", 0), message.getInt("height", 0),
                    message.getDouble("duration", -1), message.getBoolean("audio", false));
                onMedia.run();
                return;
            }
            String url = message.getString("url", "");
            String mime = message.getString("mime", "");
            int kind = NativeCore.classify(url, mime);
            if (kind == 0) return;
            String frame = message.getString("frame", "");
            Candidate item = new Candidate(url, message.getString("original", url), mime, kind, frame);
            Candidate old = media.put(frame + "\n" + url, item);
            while (media.size() > 100) media.remove(media.keySet().iterator().next());
            if (!item.equals(old) && belongsToPlayback(item)) onMedia.run();
        }, "Stream:Response", "Stream:Selection");
        runtime = GeckoRuntime.create(this);
        session = new GeckoSession();
        session.open(runtime);
        EventDispatcher.getInstance().queryBundle("Stream:Ping").accept(
            value -> { hookReady = value.getInt("version") == 2; onMedia.run(); },
            error -> { hookReady = false; onMedia.run(); });
    }

    void clearMedia() {
        playback = null;
        media.clear();
        onMedia.run();
    }

    private boolean belongsToPlayback(Candidate item) {
        return playback != null && item.frame.equals(playback.frame) &&
            (item.url.equals(playback.source) || item.original.equals(playback.source));
    }

    List<Candidate> currentMedia() {
        java.util.ArrayList<Candidate> matches = new java.util.ArrayList<>();
        for (Candidate candidate : media.values()) if (belongsToPlayback(candidate)) matches.add(candidate);
        java.util.Collections.reverse(matches);
        return matches;
    }

    boolean isCurrent(Playback selection) {
        return playback != null && selection != null && Objects.equals(playback.frame, selection.frame)
            && Objects.equals(playback.revision, selection.revision) && Objects.equals(playback.source, selection.source);
    }

    GeckoResult<GeckoBundle> resolve(String url, String frame) {
        GeckoBundle args = new GeckoBundle(); args.putString("url", url);
        if (!frame.isEmpty()) args.putString("frame", frame);
        return EventDispatcher.getInstance().queryBundle("Stream:Resolve", args);
    }

    record Playback(String frame, String revision, String source, String title,
                    int width, int height, double duration, boolean audio) { }

    record Candidate(String url, String original, String mime, int kind, String frame) {
        String label() {
            if (kind == 2) return "Поток HLS";
            if (kind == 3) return "Поток DASH";
            if (mime.startsWith("audio/")) return "Аудиофайл";
            if (mime.startsWith("video/")) return "Видеофайл";
            return "Медиафайл";
        }
    }
}

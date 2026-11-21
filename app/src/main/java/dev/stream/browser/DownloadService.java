package dev.stream.browser;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class DownloadService extends Service {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private volatile String active;
    private volatile boolean stopping;
    private volatile DirectDownload direct;
    private volatile ExtractorProcess extractor;
    private long lastUpdate;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("downloads", "Загрузки", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stream:download");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification("Подготовка загрузки", 0));
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("STOP".equals(intent.getAction())) { stopAll(); return START_NOT_STICKY; }
        String id = intent.getStringExtra("id");
        if (id == null || !id.matches("[a-f0-9-]{36}") || !queued.add(id)) return START_NOT_STICKY;
        pending.incrementAndGet();
        worker.execute(() -> {
            try { if (!stopping) download(id); }
            finally {
                queued.remove(id);
                if (pending.decrementAndGet() == 0) new Handler(getMainLooper()).post(() -> {
                    if (pending.get() == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
                });
            }
        });
        return START_NOT_STICKY;
    }
    private Notification notification(String text, int progress) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DownloadService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stream Browser").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress, progress <= 0)
            .addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build();
    }
    private int connections() {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(memory);
        ConnectivityManager network = getSystemService(ConnectivityManager.class);
        return NativeCore.parallelism(Runtime.getRuntime().availableProcessors(), memory.totalMem / (1024 * 1024),
            network.isActiveNetworkMetered(), getSystemService(PowerManager.class).isPowerSaveMode(),
            getSharedPreferences("settings", 0).getInt("connections", 0));
    }
    private void status(String id, JSONObject job, String state) throws Exception {
        job.put("state", state); SecureStore.save(this, id, job);
    }
    private void download(String id) {
        active = id;
        JSONObject job = null;
        File cookieFile = new File(getCacheDir(), "cookies-" + id + ".txt");
        try {
            wakeLock.acquire(6 * 60 * 60 * 1000L);
            job = SecureStore.read(this, id);
            status(id, job, "Загружается");
            File output = new File(getExternalFilesDir(null), "downloads/" + id);
            if (!output.isDirectory() && !output.mkdirs()) throw new DownloadFailure("Хранилище приложения недоступно.");
            if (job.optBoolean("direct") || NativeCore.classify(job.getString("url"), job.optString("mime")) == 1) {
                direct = new DirectDownload(job, () -> stopping, value -> {
                    long now = SystemClock.elapsedRealtime();
                    synchronized (this) {
                        if (now - lastUpdate > 1000) {
                            lastUpdate = now;
                            getSystemService(NotificationManager.class).notify(1, notification("Загрузка " + value + "%", value));
                        }
                    }
                });
                direct.download(output, connections());
                direct = null;
            } else {
            JSONObject headers = job.optJSONObject("headers");
            if (headers != null && headers.has("authorization")) {
                // yt-dlp's global header would send this credential to unrelated CDN hosts.
                throw new UnsupportedOperationException("AUTH_SCOPE");
            }
            ExtractorRequest request = new ExtractorRequest(job.getString("url"));
            request.addOption("--no-playlist"); request.addOption("--continue");
            request.addOption("--no-mtime"); request.addOption("--newline");
            request.addOption("--retries", 8); request.addOption("--fragment-retries", 8);
            request.addOption("--abort-on-unavailable-fragments");
            request.addOption("--socket-timeout", 30);
            int threads = connections();
            request.addOption("--concurrent-fragments", threads);
            request.addOption("--downloader", "http,ftp:libaria2c.so");
            request.addOption("--downloader-args", "aria2c:-x " + threads + " -s " + threads + " -k 1M --file-allocation=none --ca-certificate="
                + new File(getNoBackupFilesDir(), "extractor-0.18.1/python/usr/etc/tls/cert.pem").getAbsolutePath());
            request.addOption("-o", new File(output, "%(title).100B [%(id).40B].%(ext)s").getAbsolutePath());
            String format = job.optString("format",
                getSharedPreferences("settings", 0).getString("format", "bestvideo+bestaudio/best"));
            request.addOption("-f", format);
            if (headers != null) {
                for (String name : new String[]{"user-agent", "referer", "origin"}) {
                    String value = headers.optString(name);
                    if (!value.isEmpty() && !value.contains("\r") && !value.contains("\n"))
                        request.addOption("--add-header", name + ":" + value);
                }
            }
            writeCookies(job.optJSONArray("cookies"), cookieFile);
            request.addOption("--cookies", cookieFile.getAbsolutePath());
            if (stopping || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            extractor = new ExtractorProcess(this);
            extractor.execute(request.arguments(), () -> stopping, progress -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate > 1000) {
                    lastUpdate = now;
                    getSystemService(NotificationManager.class).notify(1,
                        notification("Загрузка " + progress + "%", progress));
                }
            });
            extractor = null;
            }
            status(id, job, "Сохранение в Downloads");
            File[] files = output.listFiles((dir, name) -> !name.endsWith(".part") && !name.endsWith(".ytdl") && !name.endsWith(".aria2")
                && !name.endsWith(".segment") && !name.equals("range.meta"));
            if (files == null || files.length == 0) throw new DownloadFailure("После обработки не осталось готового файла.");
            JSONArray published = job.optJSONArray("published");
            if (published == null) { published = new JSONArray(); job.put("published", published); }
            for (File file : files) {
                if (!file.isFile()) continue;
                boolean done = false;
                for (int i = 0; i < published.length(); i++) if (file.getName().equals(published.getString(i))) done = true;
                if (!done) {
                    try { publish(file); }
                    catch (InterruptedException stop) { throw stop; }
                    catch (Exception export) {
                        throw new DownloadFailure("Не удалось сохранить файл в Downloads. Проверьте свободное место.");
                    }
                    published.put(file.getName());
                    SecureStore.save(this, id, job);
                }
                file.delete();
            }
            job.remove("cookies"); job.remove("headers"); job.remove("url"); job.remove("page");
            status(id, job, "Готово · Downloads/StreamBrowser");
            File[] leftovers = output.listFiles();
            if (leftovers != null) for (File file : leftovers) if (file.isFile()) file.delete();
            output.delete();
        } catch (Exception error) {
            if (job != null) try {
                String message = stopping ? "Остановлено — можно продолжить" : errorDescription(error);
                status(id, job, message);
                android.util.Log.w("StreamDownload", message);
            } catch (Exception ignored) { }
        } finally {
            if (direct != null) { direct.cancel(); direct = null; }
            if (extractor != null) { extractor.cancel(); extractor = null; }
            cookieFile.delete(); active = null;
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }
    private String errorDescription(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DownloadFailure failure) return failure.explanation;
            if (cause instanceof java.net.SocketTimeoutException) return "Сервер не ответил вовремя.";
            if (cause instanceof java.net.UnknownHostException) return "Сайт недоступен по сети.";
            if (cause instanceof UnsupportedOperationException) return "Bearer-авторизация для этого потока пока недоступна.";
        }
        return "Ошибка загрузки — обновите источник и повторите";
    }
    private void writeCookies(JSONArray cookies, File target) throws Exception {
        try (Writer out = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
            out.write("# Netscape HTTP Cookie File\n");
            if (cookies == null) return;
            for (int i = 0; i < cookies.length(); i++) {
                JSONObject c = cookies.getJSONObject(i);
                String domain = c.getString("domain"), path = c.getString("path"), name = c.getString("name"), value = c.getString("value");
                if ((domain + path + name + value).matches("(?s).*[\\r\\n\\t].*")) continue;
                out.write((c.optBoolean("httpOnly") ? "#HttpOnly_" : "") + domain + "\t" +
                    (domain.startsWith(".") ? "TRUE" : "FALSE") + "\t" + path + "\t" +
                    (c.optBoolean("secure") ? "TRUE" : "FALSE") + "\t" + c.optLong("expires") + "\t" + name + "\t" + value + "\n");
            }
        }
    }
    private void publish(File file) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/StreamBrowser");
        String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getName());
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try {
            try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("MediaStore open failed");
                byte[] buffer = new byte[256 * 1024]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (stopping) throw new InterruptedException();
                    out.write(buffer, 0, count);
                }
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        } catch (Exception error) { getContentResolver().delete(uri, null, null); throw error; }
    }
    private void stopAll() {
        stopping = true;
        if (direct != null) direct.cancel();
        String id = active;
        if (extractor != null) extractor.cancel();
        worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onTimeout(int startId, int fgsType) { stopAll(); }
    @Override public void onDestroy() {
        stopping = true;
        if (direct != null) direct.cancel();
        if (extractor != null) extractor.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

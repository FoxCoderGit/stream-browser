package dev.stream.browser;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.*;
import org.json.*;

/** Range transport. The C++ core partitions files; every response is validated before assembly. */
final class DirectDownload {
    private final JSONObject source;
    private final URI origin;
    private final BooleanSupplier stopped;
    private final IntConsumer progress;
    private final Set<HttpURLConnection> live = ConcurrentHashMap.newKeySet();
    DirectDownload(JSONObject source, BooleanSupplier stopped, IntConsumer progress) throws Exception {
        this.source = source; this.origin = new URI(source.getString("url")); this.stopped = stopped; this.progress = progress;
    }
    private void check() throws InterruptedIOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
    void cancel() { for (HttpURLConnection c : live) c.disconnect(); }
    private HttpURLConnection open(String range, String validator) throws Exception {
        URI uri = origin;
        for (int redirects = 0; redirects < 10; redirects++) {
            check();
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null) throw new IOException("Invalid URL");
            HttpURLConnection c = (HttpURLConnection)uri.toURL().openConnection(); live.add(c);
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(20000);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) c.setRequestProperty("Range", range);
            if (validator != null) c.setRequestProperty("If-Range", validator);
            JSONObject headers = source.optJSONObject("headers");
            if (headers != null) for (String name : new String[]{"user-agent", "referer", "origin", "authorization"}) {
                String value = headers.optString(name);
                if (name.equals("authorization") && !RequestPolicy.sameOrigin(origin, uri)) continue;
                if (!value.isEmpty() && RequestPolicy.safe(value)) c.setRequestProperty(name, value);
            }
            JSONArray cookies = source.optJSONArray("cookies"); StringBuilder cookieHeader = new StringBuilder();
            if (cookies != null) for (int i = 0; i < cookies.length(); i++) {
                JSONObject cookie = cookies.getJSONObject(i);
                if (RequestPolicy.cookieMatches(uri, cookie.getString("domain"), cookie.getString("path"), cookie.optBoolean("secure"),
                    cookie.optLong("expires"), System.currentTimeMillis() / 1000)) {
                    String pair = cookie.getString("name") + "=" + cookie.getString("value");
                    if (RequestPolicy.safe(pair)) { if (cookieHeader.length() > 0) cookieHeader.append("; "); cookieHeader.append(pair); }
                }
            }
            if (cookieHeader.length() > 0) c.setRequestProperty("Cookie", cookieHeader.toString());
            int status;
            try { status = c.getResponseCode(); } catch (Exception e) { close(c); throw e; }
            if (status >= 300 && status <= 399) {
                String location = c.getHeaderField("Location"); close(c);
                if (location == null) throw new IOException("Missing redirect");
                URI next = uri.resolve(location);
                if ("https".equals(uri.getScheme()) && !"https".equals(next.getScheme())) throw new IOException("HTTPS downgrade");
                uri = next;
            } else return c;
        }
        throw new IOException("Too many redirects");
    }
    private void close(HttpURLConnection c) { c.disconnect(); live.remove(c); }
    File download(File dir, int threads) throws Exception {
        File result = new File(dir, "media" + extension());
        if (result.exists()) return result;
        HttpURLConnection probe = open("bytes=0-0", null);
        long size = -1; String etag;
        try {
            etag = probe.getHeaderField("ETag");
            String contentRange = probe.getHeaderField("Content-Range");
            if (probe.getResponseCode() == 206 && contentRange != null) {
                Matcher m = Pattern.compile("bytes 0-0/(\\d+)").matcher(contentRange);
                if (m.matches()) size = Long.parseLong(m.group(1));
            }
        } finally { close(probe); }
        if (size <= 0 || etag == null || etag.startsWith("W/")) {
            File part = new File(dir, "single.part");
            HttpURLConnection c = open(null, null);
            try {
                int status = c.getResponseCode();
                if (status != 200) throw DownloadFailure.http(status);
                long length = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    long copied = copy(in, out, -1, new AtomicLong(), length);
                    if (length >= 0 && copied != length) throw new EOFException();
                }
            } finally { close(c); }
            if (!part.renameTo(result)) throw new IOException("Rename failed");
            return result;
        }
        long[] ranges = NativeCore.ranges(size, threads);
        File meta = new File(dir, "range.meta");
        String identity = size + "\n" + etag + "\n" + Arrays.toString(ranges);
        String previous = meta.exists() ? new String(java.nio.file.Files.readAllBytes(meta.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
        if (!identity.equals(previous)) {
            File[] old = dir.listFiles((d, n) -> n.endsWith(".segment"));
            if (old != null) for (File file : old) if (!file.delete()) throw new IOException("Reset failed");
            java.nio.file.Files.write(meta.toPath(), identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        final long total = size; final String validator = etag;
        AtomicLong transferred = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(ranges.length / 2);
        ArrayList<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < ranges.length; i += 2) {
                final long start = ranges[i], end = ranges[i + 1];
                final File part = new File(dir, (i / 2) + ".segment");
                if (part.length() > end - start + 1 && !part.delete()) throw new IOException("Bad partial");
                transferred.addAndGet(part.length());
                futures.add(pool.submit(() -> {
                    for (int attempt = 0; attempt < 4; attempt++) {
                        check(); long first = start + part.length(); if (first > end) return null;
                        HttpURLConnection c = null;
                        try {
                            c = open("bytes=" + first + "-" + end, validator);
                            int status = c.getResponseCode();
                            if (status >= 400) throw DownloadFailure.http(status);
                            if (status != 206 || !("bytes " + first + "-" + end + "/" + total).equals(c.getHeaderField("Content-Range"))
                                || !validator.equals(c.getHeaderField("ETag"))) throw new IOException("Range or validator changed");
                            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, true)) {
                                copy(in, out, end - first + 1, transferred, total);
                            }
                            return null;
                        } catch (IOException error) { if (attempt == 3) throw error; Thread.sleep(500L * (attempt + 1)); }
                        finally { if (c != null) close(c); }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally { pool.shutdownNow(); cancel(); pool.awaitTermination(25, TimeUnit.SECONDS); }
        File assembled = new File(dir, "assembled.part");
        try (OutputStream out = new FileOutputStream(assembled)) {
            for (int i = 0; i < ranges.length / 2; i++) {
                File part = new File(dir, i + ".segment");
                if (part.length() != ranges[2 * i + 1] - ranges[2 * i] + 1) throw new EOFException();
                try (InputStream in = new FileInputStream(part)) { copy(in, out, -1, new AtomicLong(), -1); }
            }
        }
        if (assembled.length() != size || !assembled.renameTo(result)) throw new IOException("Assembly failed");
        return result;
    }
    private long copy(InputStream in, OutputStream out, long expected, AtomicLong transferred, long total) throws Exception {
        byte[] buffer = new byte[128 * 1024]; long copied = 0; int count;
        while ((count = in.read(buffer)) != -1) {
            check(); if (expected >= 0 && copied + count > expected) throw new IOException("Oversized range");
            out.write(buffer, 0, count); copied += count; long current = transferred.addAndGet(count);
            if (total > 0) progress.accept((int)Math.min(100, current * 100.0 / total));
        }
        if (expected >= 0 && copied != expected) throw new EOFException();
        return copied;
    }
    private String extension() {
        String path = origin.getPath();
        if (path != null) { Matcher m = Pattern.compile("\\.([a-zA-Z0-9]{1,8})$").matcher(path); if (m.find()) return "." + m.group(1); }
        String mime = source.optString("mime");
        return mime.contains("mp4") ? ".mp4" : mime.contains("webm") ? ".webm" : mime.contains("mpeg") ? ".mp3" : ".bin";
    }
}

package dev.stream.browser;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Independent CLI adapter. No Android downloader wrapper is linked into the app. */
final class ExtractorProcess {
    private final Context context;
    private volatile Process process;
    private volatile int group;
    ExtractorProcess(Context context) { this.context = context; }
    private File root() { return new File(context.getNoBackupFilesDir(), "extractor-0.18.1"); }
    private File bin(String name) { return new File(context.getApplicationInfo().nativeLibraryDir, name); }
    private void initialize() throws Exception {
        File root = root(); root.mkdirs();
        if (new File(root, "ready").exists()) return;
        String abi = Build.SUPPORTED_ABIS[0];
        unpack("libpython.zip.so", new File(root, "python"), abi);
        unpack("libffmpeg.zip.so", new File(root, "ffmpeg"), abi);
        unpack("libaria2c.zip.so", new File(root, "aria2c"), abi);
        try (InputStream in = context.getAssets().open("runtime/yt-dlp.zip"); OutputStream out = new FileOutputStream(new File(root, "yt-dlp.zip"))) {
            transfer(in, out);
        }
        if (!new File(root, "ready").createNewFile()) throw new IOException("Runtime initialization failed");
    }
    private static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[128 * 1024]; int n; while ((n = in.read(b)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            out.write(b, 0, n);
        }
    }
    private void unpack(String name, File directory, String abi) throws Exception {
        directory.mkdirs();
        JSONObject links;
        try (InputStream in = context.getAssets().open("runtime/" + abi + "-" + name + ".links.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); transfer(in, bytes);
            links = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }
        String prefix = directory.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(bin(name))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File file = new File(directory, entry.getName());
                if (!file.getCanonicalPath().startsWith(prefix)) throw new IOException("Archive path escape");
                if (links.has(entry.getName())) continue;
                if (entry.isDirectory()) { file.mkdirs(); continue; }
                file.getParentFile().mkdirs();
                try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(file)) { transfer(in, out); }
            }
        }
        Iterator<String> keys = links.keys();
        while (keys.hasNext()) {
            String entry = keys.next(); File link = new File(directory, entry); String target = links.getString(entry);
            if (new File(target).isAbsolute() || !new File(link.getParentFile(), target).getCanonicalPath().startsWith(prefix))
                throw new IOException("Archive link escape");
            java.nio.file.Files.deleteIfExists(link.toPath());
            Os.symlink(target, link.getAbsolutePath());
        }
    }
    void execute(List<String> options, BooleanSupplier stopped, IntConsumer progress) throws Exception {
        initialize();
        if (stopped.getAsBoolean()) throw new InterruptedIOException();
        List<String> command = new ArrayList<>();
        command.add(bin("libpython.so").getAbsolutePath());
        command.add("-c");
        command.add("import os,runpy,sys; os.setsid(); print('STREAM_PID:'+str(os.getpid()),flush=True); sys.argv=sys.argv[1:]; runpy.run_path(sys.argv[0],run_name='__main__')");
        command.add(new File(root(), "yt-dlp.zip").getAbsolutePath());
        command.addAll(List.of("--no-cache-dir", "--ffmpeg-location", bin("libffmpeg.so").getAbsolutePath(),
            "--js-runtimes", "quickjs:" + bin("libqjs.so").getAbsolutePath(),
            "--progress-template", "download:STREAM_PROGRESS:%(progress._percent_str)s"));
        command.addAll(options);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        File python = new File(root(), "python/usr");
        env.put("PYTHONHOME", python.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", new File(python, "lib").getAbsolutePath() + ":" + new File(root(), "ffmpeg/usr/lib").getAbsolutePath()
            + ":" + new File(root(), "aria2c/usr/lib").getAbsolutePath());
        env.put("SSL_CERT_FILE", new File(python, "etc/tls/cert.pem").getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("HOME", root().getAbsolutePath());
        env.put("PATH", context.getApplicationInfo().nativeLibraryDir + ":" + env.getOrDefault("PATH", "/system/bin"));
        Process current = builder.start(); process = current;
        Pattern percent = Pattern.compile("STREAM_PROGRESS:\\s*([0-9.]+)%");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            DownloadFailure failure = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("STREAM_PID:")) group = Integer.parseInt(line.substring(11).trim());
                if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) { cancel(); throw new InterruptedIOException(); }
                Matcher m = percent.matcher(line);
                if (m.find()) progress.accept((int)Double.parseDouble(m.group(1)));
                DownloadFailure reported = DownloadFailure.extractorLine(line);
                if (reported != null) failure = reported;
            }
            int exit = current.waitFor();
            if (exit != 0) throw failure == null
                ? new DownloadFailure("Загрузчик не смог получить файл (код " + exit + ").") : failure;
        } finally {
            cancel(); process = null; group = 0;
        }
    }
    void cancel() {
        int id = group; if (id > 1) NativeCore.terminateGroup(id);
        Process p = process; if (p != null) p.destroy();
    }
}

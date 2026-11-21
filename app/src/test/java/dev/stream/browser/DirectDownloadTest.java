package dev.stream.browser;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DirectDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private TestHttpServer server;
    private byte[] data;
    private final List<String> requestedRanges = Collections.synchronizedList(new ArrayList<>());
    @Before public void start() throws Exception {
        data = new byte[3 * 1024 * 1024 + 7]; new Random(45).nextBytes(data);
        server = TestHttpServer.create();
        server.createContext("/file.mp4", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) requestedRanges.add(range);
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            if (range == null) { exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); }
            else {
                String[] parts = range.substring(6).split("-"); int first = Integer.parseInt(parts[0]), last = Integer.parseInt(parts[1]);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + data.length);
                exchange.sendResponseHeaders(206, last - first + 1);
                exchange.getResponseBody().write(data, first, last - first + 1);
            }
            exchange.close();
        });
        server.createContext("/single.bin", exchange -> {
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
    }
    @After public void stop() { server.stop(0); }
    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private DirectDownload downloader(String path) throws Exception {
        return new DirectDownload(new JSONObject().put("url", url(path)), () -> false, progress -> {});
    }
    @Test public void serverIgnoringRangeFallsBackWithoutCorruption() throws Exception {
        File result = downloader("/single.bin").download(temporary.newFolder(), 8);
        assertArrayEquals(data, Files.readAllBytes(result.toPath()));
    }
    @Test public void nativeParallelDownloadHasExactBytes() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("stream.nativeTests"));
        File result = downloader("/file.mp4").download(temporary.newFolder(), 4);
        assertArrayEquals(data, Files.readAllBytes(result.toPath()));
    }
    @Test public void resumesValidatedPartialRange() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("stream.nativeTests"));
        File folder = temporary.newFolder();
        long[] ranges = NativeCore.ranges(data.length, 4);
        Files.write(new File(folder, "range.meta").toPath(), (data.length + "\n\"v1\"\n" + Arrays.toString(ranges)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(folder, "0.segment").toPath(), Arrays.copyOf(data, 512000));
        File result = downloader("/file.mp4").download(folder, 4);
        assertArrayEquals(data, Files.readAllBytes(result.toPath()));
        assertTrue(requestedRanges.contains("bytes=512000-" + ranges[1]));
    }
    @Test public void changedValidatorNeverPublishesMixedFile() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("stream.nativeTests"));
        server.createContext("/changed.mp4", e -> {
            String range = e.getRequestHeaders().getFirst("Range");
            String[] parts = range.substring(6).split("-"); int first = Integer.parseInt(parts[0]), last = Integer.parseInt(parts[1]);
            e.getResponseHeaders().set("ETag", range.equals("bytes=0-0") ? "\"v1\"" : "\"v2\"");
            e.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + data.length);
            e.sendResponseHeaders(206, last - first + 1);
            e.getResponseBody().write(data, first, last - first + 1); e.close();
        });
        File folder = temporary.newFolder();
        try { downloader("/changed.mp4").download(folder, 4); fail("Changed resource must be rejected"); }
        catch (java.util.concurrent.ExecutionException expected) { }
        assertFalse(new File(folder, "media.mp4").exists());
    }
    @Test public void crossOriginRedirectDropsAuthorization() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        TestHttpServer other = TestHttpServer.create();
        other.createContext("/target", e -> {
            received.set(e.getRequestHeaders().getFirst("Authorization"));
            e.sendResponseHeaders(200, 1); e.getResponseBody().write(42); e.close();
        }); other.start();
        server.createContext("/redirect", e -> {
            e.getResponseHeaders().set("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/target");
            e.sendResponseHeaders(302, -1); e.close();
        });
        try {
            JSONObject source = new JSONObject().put("url", url("/redirect"))
                .put("headers", new JSONObject().put("authorization", "Bearer SECRET"));
            File result = new DirectDownload(source, () -> false, p -> {}).download(temporary.newFolder(), 4);
            assertNull(received.get()); assertArrayEquals(new byte[]{42}, Files.readAllBytes(result.toPath()));
        } finally { other.stop(0); }
    }
    @Test public void cancellationDoesNotPublishPartialFile() throws Exception {
        try {
            new DirectDownload(new JSONObject().put("url", url("/single.bin")), () -> true, p -> {})
                .download(temporary.newFolder(), 4);
            fail("Expected cancellation");
        } catch (InterruptedIOException expected) { }
    }
}

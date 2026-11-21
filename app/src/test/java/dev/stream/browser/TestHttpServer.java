package dev.stream.browser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class TestHttpServer {
    interface Handler { void handle(Exchange exchange) throws IOException; }
    static final class Headers extends TreeMap<String, String> {
        Headers() { super(String.CASE_INSENSITIVE_ORDER); }
        void set(String key, String value) { put(key, value); }
        String getFirst(String key) { return get(key); }
    }
    static final class Exchange {
        final Socket socket;
        final Headers request = new Headers(), response = new Headers();
        Exchange(Socket socket) { this.socket = socket; }
        Headers getRequestHeaders() { return request; }
        Headers getResponseHeaders() { return response; }
        void sendResponseHeaders(int status, long size) throws IOException {
            StringBuilder text = new StringBuilder("HTTP/1.1 " + status + " Test\r\nConnection: close\r\nContent-Length: " + Math.max(0, size) + "\r\n");
            response.forEach((key, value) -> text.append(key).append(": ").append(value).append("\r\n"));
            text.append("\r\n"); socket.getOutputStream().write(text.toString().getBytes(StandardCharsets.US_ASCII));
        }
        OutputStream getResponseBody() throws IOException { return socket.getOutputStream(); }
        void close() throws IOException { socket.close(); }
    }
    private final ServerSocket socket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private TestHttpServer() throws IOException { socket = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")); }
    static TestHttpServer create() throws IOException { return new TestHttpServer(); }
    InetSocketAddress getAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
    void createContext(String path, Handler handler) { handlers.put(path, handler); }
    void start() {
        pool.submit(() -> {
            while (!socket.isClosed()) try {
                Socket client = socket.accept();
                pool.submit(() -> {
                    try (client) {
                        client.setSoTimeout(5000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String first = reader.readLine(); if (first == null) return;
                        Exchange exchange = new Exchange(client); String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            if (colon > 0) exchange.request.put(line.substring(0, colon), line.substring(colon + 1).trim());
                        }
                        Handler handler = handlers.get(first.split(" ")[1]);
                        if (handler != null) handler.handle(exchange); else exchange.sendResponseHeaders(404, 0);
                    } catch (IOException ignored) { /* Cancellation intentionally closes connections. */ }
                });
            } catch (IOException stopped) { break; }
        });
    }
    void stop(int ignored) { try { socket.close(); } catch (IOException ignoredError) {} pool.shutdownNow(); }
}

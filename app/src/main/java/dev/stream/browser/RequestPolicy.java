package dev.stream.browser;

import java.net.URI;
import java.util.Locale;

final class RequestPolicy {
    static boolean sameOrigin(URI a, URI b) {
        return a.getHost() != null && b.getHost() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
            && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    static boolean cookieMatches(URI uri, String domain, String path, boolean secure, long expires, long now) {
        if (uri.getHost() == null || (secure && !"https".equalsIgnoreCase(uri.getScheme())) || (expires > 0 && expires <= now)) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT), d = domain.toLowerCase(Locale.ROOT);
        boolean matches = d.startsWith(".") ? host.equals(d.substring(1)) || host.endsWith(d) : host.equals(d);
        String requestPath = uri.getPath();
        if (requestPath == null || requestPath.isEmpty()) requestPath = "/";
        if (path == null || path.isEmpty()) path = "/";
        return matches && (requestPath.equals(path) || requestPath.startsWith(path.endsWith("/") ? path : path + "/"));
    }
    static boolean safe(String value) { return value != null && !value.contains("\r") && !value.contains("\n"); }
}

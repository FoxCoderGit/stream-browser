package dev.stream.browser;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestPolicyTest {
    @Test public void credentialsDoNotCrossOrigin() {
        assertTrue(RequestPolicy.sameOrigin(URI.create("https://site.test/a"), URI.create("https://SITE.test:443/b")));
        assertFalse(RequestPolicy.sameOrigin(URI.create("https://site.test/a"), URI.create("https://cdn.site.test/a")));
        assertFalse(RequestPolicy.sameOrigin(URI.create("https://site.test/a"), URI.create("http://site.test/a")));
        assertFalse(RequestPolicy.sameOrigin(URI.create("https://site.test/a"), URI.create("https://site.test:444/a")));
    }
    @Test public void cookiesRespectDomainPathExpiryAndSecure() {
        URI url = URI.create("https://cdn.site.test/video/clip");
        assertTrue(RequestPolicy.cookieMatches(url, ".site.test", "/video", true, 200, 100));
        assertFalse(RequestPolicy.cookieMatches(url, "site.test", "/", true, 200, 100));
        assertFalse(RequestPolicy.cookieMatches(url, ".site.test", "/vid", true, 200, 100));
        assertFalse(RequestPolicy.cookieMatches(url, ".site.test", "/", true, 99, 100));
        assertFalse(RequestPolicy.cookieMatches(URI.create("http://cdn.site.test/video"), ".site.test", "/", true, 0, 100));
        assertFalse(RequestPolicy.cookieMatches(URI.create("https://evilsite.test/"), ".site.test", "/", false, 0, 100));
    }
    @Test public void rejectHeaderInjection() {
        assertFalse(RequestPolicy.safe("ok\r\nAuthorization: token"));
        assertTrue(RequestPolicy.safe("Mozilla/5.0"));
    }
}

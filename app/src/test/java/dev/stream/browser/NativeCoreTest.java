package dev.stream.browser;

import org.junit.*;
import static org.junit.Assert.*;

public class NativeCoreTest {
    @Before public void nativeAvailable() { Assume.assumeTrue(Boolean.getBoolean("stream.nativeTests")); }
    @Test public void rangeCoverageAndOverflow() {
        for (long size : new long[]{1, 1024, 1048576, 3000001, Long.MAX_VALUE}) {
            long[] parts = NativeCore.ranges(size, 16);
            assertEquals(0, parts[0]); assertEquals(size - 1, parts[parts.length - 1]);
            assertTrue(parts.length <= 32);
            for (int i = 0; i < parts.length; i += 2) {
                assertTrue(parts[i + 1] >= parts[i]);
                if (i > 0) assertEquals(parts[i - 1] + 1, parts[i]);
            }
        }
        assertEquals(0, NativeCore.ranges(0, 4).length);
        assertEquals(0, NativeCore.ranges(-1, 4).length);
    }
    @Test public void mediaClassification() {
        assertEquals(2, NativeCore.classify("https://a.test/live.M3U8?token=x", ""));
        assertEquals(3, NativeCore.classify("https://a.test/manifest", "application/dash+xml"));
        assertEquals(1, NativeCore.classify("https://a.test/movie", "video/mp4"));
        assertEquals(0, NativeCore.classify("https://a.test/segment.ts", "video/mp2t"));
        assertEquals(0, NativeCore.classify("blob:https://a.test/id", "video/mp4"));
    }
    @Test public void tuningRespectsExplicitSettings() {
        assertEquals(2, NativeCore.parallelism(8, 8000, false, true, 0));
        assertEquals(4, NativeCore.parallelism(16, 8000, true, false, 0));
        assertEquals(12, NativeCore.parallelism(16, 8000, false, false, 0));
        assertEquals(16, NativeCore.parallelism(8, 1000, true, true, 99));
    }
}

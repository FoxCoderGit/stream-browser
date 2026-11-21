package dev.stream.browser;

final class NativeCore {
    static { System.loadLibrary("stream_core"); }
    static native int classify(String url, String mime);
    static native int parallelism(int cores, long memoryMb, boolean metered, boolean powerSave, int overrideValue);
    static native long[] ranges(long size, int connections);
    static native void terminateGroup(int pid);
}

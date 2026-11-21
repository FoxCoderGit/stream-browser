#include <jni.h>
#include <algorithm>
#include <cctype>
#include <string>
#include <array>
#ifdef __ANDROID__
#include <signal.h>
#include <unistd.h>
#endif

namespace {
std::string str(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    std::transform(result.begin(), result.end(), result.begin(),
        [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_stream_browser_NativeCore_terminateGroup(JNIEnv*, jclass, jint pid) {
#ifdef __ANDROID__
    if (pid > 1 && pid != getpgrp()) kill(-pid, SIGKILL);
#else
    (void)pid;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_stream_browser_NativeCore_classify(JNIEnv* env, jclass, jstring url, jstring mime) {
    auto path = str(env, url);
    if (!(path.starts_with("https://") || path.starts_with("http://"))) return 0;
    path.resize(path.find_first_of("?#") == std::string::npos ? path.size() : path.find_first_of("?#"));
    const auto type = str(env, mime);
    if (path.ends_with(".m3u8") || type.find("mpegurl") != std::string::npos) return 2;
    if (path.ends_with(".mpd") || type.find("dash+xml") != std::string::npos) return 3;
    // Fragments belong to a manifest, not separate user downloads.
    if (path.ends_with(".m4s") || path.ends_with(".ts") || type.find("mp2t") != std::string::npos) return 0;
    if (type.starts_with("video/") || type.starts_with("audio/")) return 1;
    for (const auto* ext : {".mp4", ".webm", ".mkv", ".mov", ".mp3", ".m4a", ".ogg", ".wav", ".flac"})
        if (path.ends_with(ext)) return 1;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_stream_browser_NativeCore_parallelism(JNIEnv*, jclass, jint cores, jlong memoryMb,
                                             jboolean metered, jboolean powerSave, jint overrideValue) {
    if (overrideValue > 0) return std::clamp(overrideValue, 1, 16);
    if (powerSave || memoryMb < 2048) return 2;
    const int ceiling = metered ? 4 : (memoryMb >= 6000 ? 12 : 8);
    return std::clamp(static_cast<int>(cores), 2, ceiling);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_stream_browser_NativeCore_ranges(JNIEnv* env, jclass, jlong size, jint connections) {
    if (size <= 0) return env->NewLongArray(0);
    const auto count = std::min<jlong>(std::clamp(connections, 1, 16), std::max<jlong>(1, size / (1024 * 1024)));
    std::array<jlong, 32> result{};
    jlong offset = 0;
    for (jlong i = 0; i < count; ++i) {
        const jlong length = size / count + (i < size % count ? 1 : 0);
        result[2 * i] = offset;
        result[2 * i + 1] = offset + length - 1;
        offset += length;
    }
    auto array = env->NewLongArray(static_cast<jsize>(count * 2));
    if (array) env->SetLongArrayRegion(array, 0, static_cast<jsize>(count * 2), result.data());
    return array;
}

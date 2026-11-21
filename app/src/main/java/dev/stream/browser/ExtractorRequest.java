package dev.stream.browser;

import java.util.ArrayList;
import java.util.List;

final class ExtractorRequest {
    private final String url;
    private final ArrayList<String> options = new ArrayList<>();
    ExtractorRequest(String url) { this.url = url; }
    void addOption(String name) { options.add(name); }
    void addOption(String name, Object value) { options.add(name); options.add(String.valueOf(value)); }
    List<String> arguments() { ArrayList<String> result = new ArrayList<>(options); result.add("--"); result.add(url); return result; }
}

package dev.stream.browser;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BrowserLibrary {
    private final SharedPreferences preferences;

    BrowserLibrary(Context context) {
        preferences = context.getSharedPreferences("browser_library", Context.MODE_PRIVATE);
    }

    List<String> history() {
        JSONArray stored = array("history");
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < stored.length(); i++) {
            String query = stored.optString(i, "");
            if (!query.isBlank()) queries.add(query);
        }
        return queries;
    }

    void rememberSearch(String query) {
        List<String> queries = history();
        queries.remove(query);
        queries.add(0, query);
        save("history", new JSONArray(queries.subList(0, Math.min(queries.size(), 50))));
    }

    void clearHistory() {
        preferences.edit().remove("history").apply();
    }

    void forgetSearch(String query) {
        List<String> queries = history();
        queries.remove(query);
        save("history", new JSONArray(queries));
    }

    List<String> folders() {
        JSONArray stored = array("folders");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < stored.length(); i++) names.add(stored.optString(i));
        return names;
    }

    boolean createFolder(String name) {
        if (name.isBlank()) return false;
        List<String> folders = folders();
        for (String existing : folders) if (existing.equalsIgnoreCase(name)) return false;
        folders.add(name);
        save("folders", new JSONArray(folders));
        return true;
    }

    void removeFolder(String name) {
        List<String> folders = folders();
        folders.remove(name);
        JSONArray stored = array("bookmarks");
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < stored.length(); i++) {
            JSONObject page = stored.optJSONObject(i);
            if (page != null && !name.equals(page.optString("folder"))) remaining.put(page);
        }
        preferences.edit().putString("folders", new JSONArray(folders).toString())
            .putString("bookmarks", remaining.toString()).apply();
    }

    record Bookmark(String title, String url, String folder) { }

    List<Bookmark> bookmarks(String folder) {
        JSONArray stored = array("bookmarks");
        List<Bookmark> pages = new ArrayList<>();
        for (int i = 0; i < stored.length(); i++) {
            JSONObject page = stored.optJSONObject(i);
            if (page != null && folder.equals(page.optString("folder"))) {
                pages.add(new Bookmark(page.optString("title"), page.optString("url"), folder));
            }
        }
        return pages;
    }

    void addBookmark(Bookmark bookmark) throws JSONException {
        JSONArray stored = withoutBookmark(bookmark);
        stored.put(new JSONObject().put("title", bookmark.title).put("url", bookmark.url)
            .put("folder", bookmark.folder));
        save("bookmarks", stored);
    }

    void removeBookmark(Bookmark bookmark) {
        save("bookmarks", withoutBookmark(bookmark));
    }

    private JSONArray withoutBookmark(Bookmark bookmark) {
        JSONArray stored = array("bookmarks");
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < stored.length(); i++) {
            JSONObject page = stored.optJSONObject(i);
            if (page != null && !(bookmark.folder.equals(page.optString("folder"))
                && bookmark.url.equals(page.optString("url")))) {
                remaining.put(page);
            }
        }
        return remaining;
    }

    private JSONArray array(String key) {
        try {
            return new JSONArray(preferences.getString(key, "[]"));
        } catch (JSONException invalid) {
            return new JSONArray();
        }
    }

    private void save(String key, JSONArray value) {
        preferences.edit().putString(key, value.toString()).apply();
    }
}
